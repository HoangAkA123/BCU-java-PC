package common.battle.extension;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.attack.AttackSimple;
import common.battle.entity.Entity;
import common.util.Data.Proc;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Version 1 of the cat/enemy extension API. */
public interface BattleExtension {
	int API_VERSION = 1;
	/** Additive revision; existing v1 plugins remain compatible. */
	int API_REVISION = 2;

	enum Kind { ABILITY, EFFECT }
	enum Side { BOTH, CAT, ENEMY }
	enum Stacking { REFRESH, REPLACE, STACK }
	enum InterruptionType { KNOCKBACK, DAMAGE_KNOCKBACK, BOSS_WAVE, SNIPER, WARP }
	enum Status {
		STOP(common.util.Data.P_STOP), SLOW(common.util.Data.P_SLOW), WEAK(common.util.Data.P_WEAK),
		CURSE(common.util.Data.P_CURSE), SEAL(common.util.Data.P_SEAL), POISON(common.util.Data.P_POISON),
		ARMOR(common.util.Data.P_ARMOR), SPEED(common.util.Data.P_SPEED), LETHARGY(common.util.Data.P_LETHARGY);
		public final int index;
		Status(int index) { this.index = index; }
	}

	String id();
	String name();
	default int apiVersion() { return API_VERSION; }
	default String description() { return ""; }
	default Kind kind() { return Kind.ABILITY; }
	default Side side() { return Side.BOTH; }
	default List<Parameter> parameters() { return Collections.emptyList(); }
	Behavior create(Settings settings);

	interface Behavior {
		default void onStart(Context context) {}
		default void onTick(Context context) {}
		default void onAttackStart(Context context) {}
		default int outgoingDamage(Hit hit, int damage) { return damage; }
		default int incomingDamage(Hit hit, int damage) { return damage; }
		default void onHit(Hit hit, int damage) {}
		default void onDamaged(Hit hit, int damage) {}
		default void onDeath(Context context) {}
		default void onKill(Hit hit) {}
		default void onEnd(Context context) {}
		default void onRender(Visual visual) {}
		default boolean allowInterruption(Interruption interruption) { return true; }
		/** Return false to reject the entire hit, before native damage/proc processing. */
		default boolean allowHit(Hit hit) { return true; }
		/** Per-target copy: clearing/changing a proc never changes another target's copy. */
		default void incomingProcs(Hit hit, Proc procs) {}
		/** Runs before an entity attack is constructed; also applies to special attack slots. */
		default void beforeAttack(Context context, AttackDraft attack) {}
		/** Signed distance in world coordinates for voluntary walking. */
		default float movement(Context context, float distance) { return distance; }
		/** Signed HP delta: positive heals, negative damages, after native armor. */
		default long healthChange(Context context, long delta) { return delta; }
		default void onHealthChanged(Context context, long previousHealth) {}
		/** Immediate revival on lethal HP loss. Return 0 to leave native death/revival unchanged. */
		default long reviveHealth(Context context) { return 0; }
		/** Native resurrection begins, or an immediate plugin resurrection succeeds. */
		default void onRevive(Context context) {}
		default boolean allowEffect(Context context, Entity source, String effectId) { return true; }
	}

	final class Parameter {
		public final String key, label, defaultValue;
		private final String type;
		private final double min, max;

		private Parameter(String key, String label, String value, String type, double min, double max) {
			this.key = key;
			this.label = label;
			this.type = type;
			this.min = min;
			this.max = max;
			defaultValue = validate(value);
		}

		public static Parameter integer(String key, String label, int value, int min, int max) {
			return new Parameter(key, label, Integer.toString(value), "int", min, max);
		}

		public static Parameter decimal(String key, String label, double value, double min, double max) {
			return new Parameter(key, label, Double.toString(value), "decimal", min, max);
		}

		public static Parameter flag(String key, String label, boolean value) {
			return new Parameter(key, label, Boolean.toString(value), "boolean", 0, 1);
		}

		public static Parameter text(String key, String label, String value) {
			return new Parameter(key, label, value, "text", 0, 0);
		}

		public String validate(String input) {
			switch (type) {
			case "int":
				int integer = Integer.parseInt(input.trim());
				checkRange(integer);
				return Integer.toString(integer);
			case "decimal":
				double decimal = Double.parseDouble(input.trim());
				checkRange(decimal);
				return Double.toString(decimal);
			case "boolean":
				String value = input.trim();
				if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
					throw new IllegalArgumentException(label + " requires true or false");
				return Boolean.toString(Boolean.parseBoolean(value));
			default:
				return input;
			}
		}

		private void checkRange(double value) {
			if (!Double.isFinite(value) || !(value >= min && value <= max))
				throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
		}
	}

	final class Settings {
		private final JsonObject values = new JsonObject();

		public Settings(List<Parameter> parameters, String encoded) {
			JsonObject stored = new JsonObject();
			try {
				if (encoded != null)
					stored = JsonParser.parseString(encoded).getAsJsonObject();
			} catch (RuntimeException ignored) {
				// Invalid saved settings fall back to the declared defaults.
			}
			for (Parameter parameter : parameters) {
				String value = parameter.defaultValue;
				try {
					if (stored.has(parameter.key))
						value = parameter.validate(stored.get(parameter.key).getAsString());
				} catch (RuntimeException ignored) {
					// A setting may be outside the range of an updated extension.
				}
				values.addProperty(parameter.key, value);
			}
		}

		public String text(String key) { return values.get(key).getAsString(); }
		public int integer(String key) { return Integer.parseInt(text(key)); }
		public double decimal(String key) { return Double.parseDouble(text(key)); }
		public boolean flag(String key) { return Boolean.parseBoolean(text(key)); }
	}

	final class Context {
		public final Entity self;
		public final Entity source;
		public final String id;
		private long tick;
		private final Map<String, Long> cooldowns = new HashMap<>();
		private static final ThreadLocal<Integer> SUMMON_DEPTH = ThreadLocal.withInitial(() -> 0);

		Context(Entity self, Entity source, String id) {
			this.self = self;
			this.source = source;
			this.id = id;
		}

		public StageBasis battle() { return self.basis; }
		public boolean chance(double percent) { return self.basis.r.nextFloat() * 100 < percent; }
		public void heal(long amount) { self.queueExtensionDamage(-Math.max(0, amount)); }
		public void damage(long amount) { self.queueExtensionDamage(Math.max(0, amount)); }
		public void cancelDisplacement(Entity target) { target.cancelExtensionDisplacement(); }
		public void heal(Entity target, long amount) { target.queueExtensionDamage(-Math.max(0, amount)); }
		public void damage(Entity target, long amount) { target.queueExtensionDamage(Math.max(0, amount)); }
		public long age() { return tick; }
		public int statusTime(Entity target, Status status) { return target.extensionStatusTime(status); }
		public void clearStatus(Entity target, Status status) { target.clearExtensionStatus(status); }
		void advance() { tick++; }
		/** Consume a named cooldown when ready. Simulation ticks, never wall-clock time. */
		public boolean cooldown(String key, int ticks) {
			if (ticks < 1) throw new IllegalArgumentException("Cooldown must be positive");
			Long ready = cooldowns.get(key);
			if (ready != null && ready > tick) return false;
			cooldowns.put(key, tick + ticks);
			return true;
		}
		/** Stable battle-list order, living non-base targets; includes self when allies=true. */
		public List<Entity> targets(float radius, boolean allies) {
			if (!Float.isFinite(radius) || radius < 0) throw new IllegalArgumentException("Invalid radius");
			List<Entity> result = new ArrayList<>();
			for (Entity target : battle().le)
				if (target.isExtensionActive() && !target.isBase()
						&& (target.dire == self.dire) == allies && Math.abs(target.pos - self.pos) <= radius)
					result.add(target);
			return Collections.unmodifiableList(result);
		}
		/** Queue a native area attack. Distances are relative to self, positive toward the enemy. */
		public boolean attack(int damage, float from, float to, Proc procs) {
			if (!self.isExtensionActive() || self.extensionAttackModel() == null) return false;
			if (!Float.isFinite(from) || !Float.isFinite(to)) throw new IllegalArgumentException("Invalid range");
			AttackDraft draft = new AttackDraft(-1, damage, self.pos + from * self.dire,
					self.pos + to * self.dire, procs == null ? Proc.blank() : procs.clone());
			self.prepareExtensionAttack(draft);
			if (draft.cancelled) return false;
			battle().queueExtensionAttack(new AttackSimple(self, self.extensionAttackModel(), draft.damage, self.traits, self.getAbi(),
					draft.procs, draft.start, draft.end, true, self.data.getAtkModel(0), self.currentLayer, false, 1));
			return true;
		}
		/** Uses native summon rules, limits, delay, level/magnification, form and HP bonds. */
		public boolean summon(Proc.SUMMON configuration) {
			if (configuration == null || configuration.id == null || configuration.id.get() == null
					|| configuration.time < 0 || self.extensionAttackModel() == null || SUMMON_DEPTH.get() >= 8) return false;
			Proc.SUMMON copy = (Proc.SUMMON) configuration.clone();
			Object unit = copy.id.get();
			if (unit instanceof common.util.unit.Unit) {
				int forms = ((common.util.unit.Unit) unit).forms.length;
				if (forms == 0) return false;
				copy.form = Math.max(1, Math.min(copy.form, forms));
			} else if (!(unit instanceof common.util.unit.AbEnemy)) return false;
			int depth = SUMMON_DEPTH.get();
			SUMMON_DEPTH.set(depth + 1);
			try {
				int count = battle().tempe.size();
				self.extensionAttackModel().summon(copy, self, new common.util.BattleObj(), 0);
				return battle().tempe.size() > count;
			} finally { SUMMON_DEPTH.set(depth); }
		}

		public boolean applyEffect(Entity target, String effectId, int ticks, Stacking stacking) {
			return applyEffect(target, effectId, ticks, stacking, null);
		}

		/** Settings are a JSON object with parameter keys and values. Null uses defaults. */
		public boolean applyEffect(Entity target, String effectId, int ticks, Stacking stacking, String settings) {
			return target.applyBattleEffect(self, effectId, ticks, stacking, settings);
		}

		/** Applies a temporary behavior created entirely by the calling plugin. */
		public boolean applyEffect(Entity target, String instanceId, Behavior behavior, int ticks, Stacking stacking) {
			return target.applyBattleEffect(self, instanceId, behavior, ticks, stacking);
		}

		public void removeEffect(Entity target, String effectId) {
			target.removeBattleEffect(effectId);
		}
	}

	/** Mutable draft; proc values follow BCU's native Proc schema. Cancellation is sticky. */
	final class AttackDraft {
		public final int index;
		public int damage;
		public float start, end;
		public Proc procs;
		public boolean cancelled;
		public AttackDraft(int index, int damage, float start, float end, Proc procs) {
			this.index = index;
			this.damage = damage;
			this.start = start;
			this.end = end;
			this.procs = procs;
		}
	}

	final class Interruption {
		public final Context context;
		public final InterruptionType type;
		public final float distance;

		Interruption(Context context, InterruptionType type, float distance) {
			this.context = context;
			this.type = type;
			this.distance = distance;
		}
	}

	/** Entity-anchored drawing. Coordinates and sizes are relative to the battle zoom. */
	final class Visual {
		private final FakeGraphics graphics;
		public final float x, y, scale;

		Visual(FakeGraphics graphics, float x, float y, float scale) {
			this.graphics = graphics;
			this.x = x;
			this.y = y;
			this.scale = scale;
		}

		public void rectangle(float dx, float dy, float width, float height, int r, int g, int b, int a) {
			graphics.colRect(x + dx * scale, y + dy * scale, width * scale, height * scale, r, g, b, a);
		}

		public void image(FakeImage image, float dx, float dy, float width, float height) {
			graphics.drawImage(image, x + dx * scale, y + dy * scale, width * scale, height * scale);
		}
	}

	final class Hit {
		public final Context context;
		public final Entity target;
		public final AttackAb attack;

		Hit(Context context, Entity target, AttackAb attack) {
			this.context = context;
			this.target = target;
			this.attack = attack;
		}
	}
}
