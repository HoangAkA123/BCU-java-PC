package common.battle.extension;

import common.battle.StageBasis;
import common.battle.attack.AttackAb;
import common.battle.entity.Entity;
import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;

/** Version 1 of the cat/enemy extension API. */
public interface BattleExtension {
	int API_VERSION = 1;

	enum Kind { ABILITY, EFFECT }
	enum Side { BOTH, CAT, ENEMY }
	enum Stacking { REFRESH, REPLACE, STACK }
	enum InterruptionType { KNOCKBACK, DAMAGE_KNOCKBACK, BOSS_WAVE, SNIPER, WARP }

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
