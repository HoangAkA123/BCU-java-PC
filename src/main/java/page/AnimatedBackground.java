package page;

import main.MainBCU;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.scale.AWTUtil;

import javax.swing.ImageIcon;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AnimatedBackground {
	private static final int REORDER_WINDOW = 16;
	private static final int MAX_FRAME_SIDE = 640;
	private static final int BACKGROUND_FPS = 12;

	private final MainFrame owner;
	private final Timer refreshTimer;
	private volatile boolean visible = true;
	private Playback active;
	private Playback pending;

	AnimatedBackground(MainFrame owner) {
		this.owner = owner;
		refreshTimer = new Timer(1000 / BACKGROUND_FPS, event -> refresh());
	}

	private void refresh() {
		boolean wasVisible = visible;
		visible = owner.isShowing() && (owner.getExtendedState() & Frame.ICONIFIED) == 0;
		if (visible && active != null && (active.dirty.getAndSet(false) || !wasVisible))
			repaintPage();
	}

	private void repaintPage() {
		Page page = MainFrame.getPanel();
		if (page != null)
			page.repaint();
	}

	void load(File file, boolean reportErrors) {
		if (pending != null)
			pending.stop();
		pending = new Playback(file.getAbsoluteFile(), reportErrors);
		pending.thread.start();
	}

	void clear() {
		if (pending != null)
			pending.stop();
		if (active != null)
			active.stop();
		active = pending = null;
		refreshTimer.stop();
		visible = true;
		MainBCU.animatedBackgroundPath = "";
		repaintPage();
	}

	void paint(Graphics graphics, Page page) {
		Playback playback = active;
		Image image = playback == null ? null : playback.image;
		if (image == null || page.getWidth() == 0 || page.getHeight() == 0)
			return;
		int width = image.getWidth(playback);
		int height = image.getHeight(playback);
		if (width <= 0 || height <= 0)
			return;

		double scale = Math.max((double) page.getWidth() / width, (double) page.getHeight() / height);
		int scaledWidth = (int) Math.ceil(width * scale);
		int scaledHeight = (int) Math.ceil(height * scale);
		Graphics2D layer = (Graphics2D) graphics.create();
		try {
			layer.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			layer.setComposite(AlphaComposite.SrcOver.derive(0.45f));
			layer.drawImage(image, (page.getWidth() - scaledWidth) / 2, (page.getHeight() - scaledHeight) / 2,
					scaledWidth, scaledHeight, playback);
		} finally {
			layer.dispose();
		}
	}

	private final class Playback implements Runnable, ImageObserver {
		private final File file;
		private final boolean reportErrors;
		private final Thread thread;
		private volatile Image image;
		private final AtomicBoolean dirty = new AtomicBoolean();
		private boolean firstFrame = true;

		Playback(File file, boolean reportErrors) {
			this.file = file;
			this.reportErrors = reportErrors;
			thread = new Thread(this, "BCU animated background");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
		}

		@Override
		public boolean imageUpdate(Image source, int flags, int x, int y, int width, int height) {
			if (thread.isInterrupted() || (flags & (ERROR | ABORT)) != 0)
				return false;
			if ((flags & (FRAMEBITS | ALLBITS)) != 0)
				dirty.set(true);
			return visible && (flags & ALLBITS) == 0;
		}

		void stop() {
			thread.interrupt();
			Image previous = image;
			if (previous != null)
				previous.flush();
		}

		@Override
		public void run() {
			try {
				String name = file.getName().toLowerCase(Locale.ROOT);
				if (name.endsWith(".gif"))
					loadGif();
				else if (name.endsWith(".mp4"))
					playVideo();
				else
					throw new IOException("Choose a GIF or an H.264 MP4 file.");
			} catch (InterruptedException cancelled) {
				Thread.currentThread().interrupt();
			} catch (Exception failure) {
				if (!Thread.currentThread().isInterrupted())
					SwingUtilities.invokeLater(() -> failed(failure));
			}
		}

		private void loadGif() throws IOException {
			byte[] header = new byte[6];
			try (DataInputStream input = new DataInputStream(Files.newInputStream(file.toPath()))) {
				input.readFully(header);
			}
			String signature = new String(header, StandardCharsets.US_ASCII);
			if (!signature.equals("GIF87a") && !signature.equals("GIF89a"))
				throw new IOException("The selected file is not a GIF.");

			Image gif = Toolkit.getDefaultToolkit().createImage(file.toURI().toURL());
			ImageIcon icon = new ImageIcon(gif);
			// AWT can report ABORTED when the tracker's observer detaches from an animated GIF.
			if ((icon.getImageLoadStatus() & MediaTracker.ERRORED) != 0
					|| icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) {
				gif.flush();
				throw new IOException("The GIF could not be decoded.");
			}
			publish(gif);
		}

		private void playVideo() throws Exception {
			while (!Thread.currentThread().isInterrupted()) {
				try (SeekableByteChannel channel = NIOUtils.readableChannel(file)) {
					FrameGrab grab = FrameGrab.createFrameGrab(channel);
					PriorityQueue<VideoFrame> frames = new PriorityQueue<>(Comparator.comparingDouble(f -> f.timestamp));
					Set<Long> queuedSlots = new HashSet<>();
					long lastSlot = Long.MIN_VALUE;
					long decoded = 0;
					double end = 0;
					long clock = System.nanoTime();
					while (!Thread.currentThread().isInterrupted()) {
						clock += waitUntilVisible();
						PictureWithMetadata frame = grab.getNativeFrameWithMetadata();
						if (frame == null)
							break;

						// Decode reference frames as required by H.264, but only convert selected frames to RGB.
						double timestamp = frame.getTimestamp();
						double duration = frame.getDuration();
						end = Math.max(end, timestamp + (duration > 0 ? duration : 1.0 / BACKGROUND_FPS));
						long slot = Math.round(timestamp * BACKGROUND_FPS);
						if (slot > lastSlot && queuedSlots.add(slot))
							frames.add(new VideoFrame(frame, slot, decoded));
						decoded++;

						// Allow reference frames to arrive before earlier presentation frames.
						while (!frames.isEmpty() && decoded - frames.peek().decodeIndex > REORDER_WINDOW) {
							VideoFrame next = frames.remove();
							queuedSlots.remove(next.slot);
							clock = display(next, clock);
							lastSlot = next.slot;
						}
					}
					if (decoded == 0 && !Thread.currentThread().isInterrupted())
						throw new IOException("The MP4 does not contain a playable H.264 video track.");
					while (!frames.isEmpty() && !Thread.currentThread().isInterrupted())
						clock = display(frames.remove(), clock);
					sleepUntil(clock + secondsToNanos(end));
				}
			}
		}

		private long waitUntilVisible() throws InterruptedException {
			if (visible || firstFrame)
				return 0;
			long start = System.nanoTime();
			while (!visible)
				Thread.sleep(100);
			return System.nanoTime() - start;
		}

		private long display(VideoFrame frame, long clock) throws InterruptedException {
			clock += waitUntilVisible();
			long offset = secondsToNanos(frame.timestamp);
			// If decoding falls behind, rebase the clock instead of drawing a burst of stale frames.
			clock = Math.max(clock, System.nanoTime() - offset);
			sleepUntil(clock + offset);
			publish(frame.image);
			return clock;
		}

		private long secondsToNanos(double seconds) {
			return (long) (seconds * 1_000_000_000L);
		}

		private void sleepUntil(long deadline) throws InterruptedException {
			TimeUnit.NANOSECONDS.sleep(deadline - System.nanoTime());
		}

		private void publish(Image next) {
			if (Thread.currentThread().isInterrupted()) {
				next.flush();
				return;
			}
			image = next;
			dirty.set(true);
			if (firstFrame) {
				firstFrame = false;
				SwingUtilities.invokeLater(() -> {
					if (pending != this)
						return;
					if (active != null)
						active.stop();
					active = this;
					pending = null;
					MainBCU.animatedBackgroundPath = file.getPath();
					refreshTimer.start();
				});
			}
		}

		private void failed(Exception failure) {
			if (pending == this) {
				pending = null;
			} else if (active == this) {
				active = null;
				refreshTimer.stop();
				visible = true;
				MainBCU.animatedBackgroundPath = "";
				repaintPage();
			} else {
				return;
			}

			if (reportErrors) {
				JOptionPane.showMessageDialog(owner, "Could not play " + file.getName()
						+ ". Choose a readable GIF or H.264 MP4.", "Animated background", JOptionPane.ERROR_MESSAGE);
			}
			failure.printStackTrace();
		}
	}

	private static final class VideoFrame {
		private final BufferedImage image;
		private final double timestamp;
		private final long slot;
		private final long decodeIndex;

		VideoFrame(PictureWithMetadata frame, long slot, long decodeIndex) {
			BufferedImage decoded = AWTUtil.toBufferedImage(frame.getPicture(), frame.getOrientation());
			int longest = Math.max(decoded.getWidth(), decoded.getHeight());
			if (longest > MAX_FRAME_SIDE) {
				int width = Math.max(1, decoded.getWidth() * MAX_FRAME_SIDE / longest);
				int height = Math.max(1, decoded.getHeight() * MAX_FRAME_SIDE / longest);
				image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
				Graphics2D graphics = image.createGraphics();
				try {
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					graphics.drawImage(decoded, 0, 0, width, height, null);
				} finally {
					graphics.dispose();
				}
			} else {
				image = decoded;
			}
			timestamp = frame.getTimestamp();
			this.slot = slot;
			this.decodeIndex = decodeIndex;
		}
	}
}
