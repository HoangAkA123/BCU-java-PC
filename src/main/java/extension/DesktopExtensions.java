package extension;

import common.battle.extension.BattleExtension;
import common.battle.extension.BattleExtensions;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class DesktopExtensions {
	private static final String[] ICON_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif"};
	private static Map<String, ImageIcon> icons = Collections.emptyMap();

	private DesktopExtensions() {}

	public static ImageIcon icon(String id) {
		return icons.get(id);
	}

	public static void load(Path folder) {
		Map<String, BattleExtension> entries = new LinkedHashMap<>();
		Map<String, ImageIcon> loadedIcons = new HashMap<>();
		if (Files.isDirectory(folder)) {
			try (Stream<Path> files = Files.list(folder)) {
				files.filter(Files::isRegularFile)
						.filter(path -> path.getFileName().toString().endsWith(".java"))
						.sorted()
						.forEach(source -> loadOne(source, entries, loadedIcons));
			} catch (IOException failure) {
				System.err.println("Could not read BCU extension folder " + folder + ": " + failure);
			}
		}
		icons = Collections.unmodifiableMap(loadedIcons);
		BattleExtensions.install(entries);
	}

	private static void loadOne(Path source, Map<String, BattleExtension> entries, Map<String, ImageIcon> loadedIcons) {
		String filename = source.getFileName().toString();
		String stem = filename.substring(0, filename.length() - ".java".length());
		Path output = null;
		URLClassLoader loader = null;
		boolean registered = false;
		try {
			ImageIcon icon = loadIcon(source, stem);
			output = Files.createTempDirectory("bcu-extension-");
			compile(source, output);
			String className = entryClass(output, stem);
			loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, DesktopExtensions.class.getClassLoader());
			Class<?> type = Class.forName(className, false, loader);
			if (type.getClassLoader() != loader)
				throw new IllegalArgumentException("class name conflicts with an installed class: " + className);
			BattleExtension definition = type.asSubclass(BattleExtension.class).getConstructor().newInstance();
			validate(definition);
			String id = definition.id();
			if (entries.containsKey(id))
				throw new IllegalArgumentException("duplicate extension ID: " + id);
			entries.put(id, definition);
			loadedIcons.put(id, icon);
			registered = true;
		} catch (Exception | LinkageError failure) {
			System.err.println("BCU extension skipped (" + filename + "): " + failure);
		} finally {
			try {
				if (!registered && loader != null)
					loader.close();
				if (output != null) {
					try (Stream<Path> files = Files.walk(output)) {
						if (registered)
							files.sorted().forEach(path -> path.toFile().deleteOnExit());
						else
							for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
								Files.deleteIfExists(path);
					}
				}
			} catch (IOException cleanupFailure) {
				System.err.println("BCU extension temporary file cleanup failed: " + cleanupFailure);
			}
		}
	}

	private static void compile(Path source, Path output) throws IOException {
		StringWriter diagnostics = new StringWriter();
		try (PrintWriter messages = new PrintWriter(diagnostics)) {
			String[] options = {"-proc:none", "-source", "8", "-target", "8", "-encoding", "UTF-8",
					"-classpath", System.getProperty("java.class.path"), "-d", output.toString(), source.toString()};
			if (!BatchCompiler.compile(options, messages, messages, null))
				throw new IOException(diagnostics.toString().trim());
		}
	}

	private static String entryClass(Path output, String stem) throws IOException {
		try (Stream<Path> files = Files.walk(output)) {
			Path file = files.filter(path -> path.getFileName().toString().equals(stem + ".class"))
					.findFirst().orElseThrow(() -> new IOException("expected public class " + stem));
			String relative = output.relativize(file).toString();
			return relative.substring(0, relative.length() - ".class".length()).replace(java.io.File.separatorChar, '.');
		}
	}

	private static ImageIcon loadIcon(Path source, String stem) throws IOException {
		BufferedImage original = null;
		for (String suffix : ICON_SUFFIXES) {
			Path icon = source.resolveSibling(stem + suffix);
			if (Files.isRegularFile(icon)) {
				original = ImageIO.read(icon.toFile());
				if (original != null)
					break;
			}
		}
		if (original == null)
			throw new IOException("missing or invalid same-name icon for " + stem);
		double scale = 32.0 / Math.max(original.getWidth(), original.getHeight());
		int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(original, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		return new ImageIcon(image);
	}

	private static void validate(BattleExtension definition) {
		if (definition.apiVersion() != BattleExtension.API_VERSION)
			throw new IllegalArgumentException("unsupported API version: " + definition.apiVersion());
		if (!definition.id().matches("[a-z][a-z0-9_.-]{0,79}") || definition.name().trim().isEmpty())
			throw new IllegalArgumentException("invalid ID or name");
		Objects.requireNonNull(definition.kind());
		Objects.requireNonNull(definition.side());
		Set<String> keys = new HashSet<>();
		for (BattleExtension.Parameter parameter : definition.parameters())
			if (!parameter.key.matches("[a-z][a-zA-Z0-9_]{0,39}") || !keys.add(parameter.key))
				throw new IllegalArgumentException("invalid or duplicate parameter key: " + parameter.key);
	}
}
