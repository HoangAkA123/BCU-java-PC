package common.battle.extension;

import common.battle.attack.AttackAb;
import common.battle.data.CustomEntity;
import common.battle.entity.Entity;
import common.battle.extension.BattleExtension.*;
import common.system.fake.FakeGraphics;
import common.util.Data.Proc;

import java.util.*;
import java.util.function.Consumer;

/** Registry and per-entity dispatch, shared by the battle engine and desktop editor. */
public final class BattleExtensions {
	private static volatile Map<String, BattleExtension> definitions = Collections.emptyMap();

	private BattleExtensions() {}

	public static void install(Map<String, BattleExtension> entries) {
		definitions = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
	}

	public static Collection<BattleExtension> definitions() {
		return definitions.values();
	}

	public static boolean supports(BattleExtension extension, boolean enemy) {
		return extension.side() == Side.BOTH || extension.side() == (enemy ? Side.ENEMY : Side.CAT);
	}

	public static final class Runtime {
		private static final int MAX_EFFECTS = 64;
		private final Entity owner;
		private final List<Instance> abilities = new ArrayList<>();
		private final List<Instance> effects = new ArrayList<>();
		private volatile Instance[] visuals = new Instance[0];
		private boolean started;
		private boolean preparingAttack;

		public Runtime(Entity owner) { this.owner = owner; }

		public void start() {
			if (started || !(owner.data instanceof CustomEntity))
				return;
			started = true;
			CustomEntity data = (CustomEntity) owner.data;
			data.extensionSettings().forEach((id, configuration) -> {
				BattleExtension definition = definitions.get(id);
				if (definition != null && supports(definition, owner.dire == 1)) {
					Instance instance = create(definition, owner, configuration, 0);
					if (instance != null)
						abilities.add(instance);
				}
			});
			for (Instance instance : abilities)
				instance.call(behavior -> behavior.onStart(instance.context));
			updateVisuals();
		}

		public boolean apply(Entity source, String id, int ticks, Stacking stacking, String configuration) {
			BattleExtension definition = definitions.get(id);
			if (definition == null || definition.kind() != Kind.EFFECT || ticks <= 0
					|| !supports(definition, owner.dire == 1))
				return false;
			if (!allowEffect(source, id)) return false;
			if (stacking == Stacking.REFRESH) {
				for (Instance instance : effects)
					if (instance.context.id.equals(id)) {
						instance.ticks = ticks;
						return true;
					}
			}
			if (stacking != Stacking.REPLACE && effects.size() >= MAX_EFFECTS)
				return false;
			Instance instance = create(definition, source, configuration, ticks);
			if (instance == null)
				return false;
			if (stacking == Stacking.REPLACE)
				remove(id);
			if (effects.size() >= MAX_EFFECTS)
				return false;
			effects.add(instance);
			instance.call(behavior -> behavior.onStart(instance.context));
			updateVisuals();
			return true;
		}

		public boolean apply(Entity source, String id, Behavior behavior, int ticks, Stacking stacking) {
			if (behavior == null || id == null || !id.matches("[a-z][a-z0-9_.-]{0,79}") || ticks <= 0)
				return false;
			if (!allowEffect(source, id)) return false;
			if (stacking == Stacking.REFRESH) {
				for (Instance instance : effects)
					if (instance.context.id.equals(id)) {
						instance.ticks = Math.max(instance.ticks, ticks);
						return true;
					}
			}
			if (stacking != Stacking.REPLACE && effects.size() >= MAX_EFFECTS)
				return false;
			if (stacking == Stacking.REPLACE)
				remove(id);
			if (effects.size() >= MAX_EFFECTS)
				return false;
			Instance instance = new Instance(new Context(owner, source, id), behavior, ticks);
			effects.add(instance);
			instance.call(value -> value.onStart(instance.context));
			updateVisuals();
			return true;
		}

		public void remove(String id) {
			List<Instance> removed = new ArrayList<>();
			for (Iterator<Instance> iterator = effects.iterator(); iterator.hasNext();) {
				Instance instance = iterator.next();
				if (instance.context.id.equals(id)) {
					iterator.remove();
					removed.add(instance);
				}
			}
			removed.forEach(Instance::end);
			if (!removed.isEmpty())
				updateVisuals();
		}

		public void tick() {
			for (Instance instance : abilities) {
				instance.context.advance();
				instance.call(behavior -> behavior.onTick(instance.context));
			}
			if (effects.isEmpty())
				return;
			for (Instance instance : effects.toArray(new Instance[0])) {
				if (!effects.contains(instance))
					continue;
				instance.context.advance();
				instance.call(behavior -> behavior.onTick(instance.context));
				if (--instance.ticks == 0 && effects.remove(instance)) {
					instance.end();
					updateVisuals();
				}
			}
		}

		public void attackStart() {
			for (Instance instance : active())
				instance.call(behavior -> behavior.onAttackStart(instance.context));
		}

		public boolean allowHit(AttackAb attack) {
			boolean allowed = true;
			for (Instance instance : active())
				allowed &= instance.value(b -> b.allowHit(new Hit(instance.context, owner, attack)), true);
			return allowed;
		}

		private boolean allowEffect(Entity source, String id) {
			boolean allowed = true;
			for (Instance instance : active())
				allowed &= instance.value(b -> b.allowEffect(instance.context, source, id), true);
			return allowed;
		}

		public Proc incomingProcs(AttackAb attack) {
			Proc result = attack.getProc();
			for (Instance instance : active()) {
				if (instance.disabled || !instance.filtersProcs) continue;
				// Commit only successful callbacks, so a broken plugin cannot partially corrupt a hit.
				Proc copy = result.clone();
				instance.call(b -> b.incomingProcs(new Hit(instance.context, owner, attack), copy));
				if (!instance.disabled) result = copy;
			}
			return result;
		}

		public void prepareAttack(AttackDraft attack) {
			if (preparingAttack) throw new IllegalStateException("Do not create attacks inside beforeAttack");
			preparingAttack = true;
			try {
				for (Instance instance : active()) {
					if (instance.disabled || !instance.preparesAttacks) continue;
					AttackDraft copy = new AttackDraft(attack.index, attack.damage, attack.start, attack.end, attack.procs.clone());
					copy.cancelled = attack.cancelled;
					instance.call(b -> {
						b.beforeAttack(instance.context, copy);
						Objects.requireNonNull(copy.procs, "Attack procs");
						if (!Float.isFinite(copy.start) || !Float.isFinite(copy.end))
							throw new IllegalArgumentException("Non-finite attack range");
					});
					if (!instance.disabled) {
						attack.damage = copy.damage;
						attack.start = copy.start;
						attack.end = copy.end;
						attack.procs = copy.procs;
						attack.cancelled |= copy.cancelled;
					}
				}
			} finally { preparingAttack = false; }
		}

		public float movement(float distance) {
			for (Instance instance : active()) {
				final float previous = distance;
				distance = instance.value(b -> {
					float value = b.movement(instance.context, previous);
					if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite movement");
					return value;
				}, previous);
			}
			return distance;
		}

		public long healthChange(long delta) {
			for (Instance instance : active()) {
				final long previous = delta;
				delta = instance.value(b -> b.healthChange(instance.context, previous), previous);
				delta = Math.max(-owner.maxH, Math.min(owner.maxH, delta));
			}
			return delta;
		}

		public void healthChanged(long previous) {
			for (Instance instance : active())
				instance.call(b -> b.onHealthChanged(instance.context, previous));
		}

		public long reviveHealth() {
			for (Instance instance : active()) {
				long health = instance.value(b -> b.reviveHealth(instance.context), 0L);
				if (health > 0) return Math.min(owner.maxH, health);
			}
			return 0;
		}

		public void revived() {
			for (Instance instance : active())
				instance.call(b -> b.onRevive(instance.context));
		}

		public void killed(AttackAb attack, Entity target) {
			for (Instance instance : active())
				instance.call(behavior -> behavior.onKill(new Hit(instance.context, target, attack)));
		}

		public void death() {
			for (Instance instance : abilities)
				instance.call(behavior -> behavior.onDeath(instance.context));
			Instance[] ended = effects.toArray(new Instance[0]);
			effects.clear();
			for (Instance instance : ended) {
				instance.call(behavior -> behavior.onDeath(instance.context));
				instance.end();
			}
			updateVisuals();
		}

		public int modify(AttackAb attack, Entity target, int damage, boolean outgoing) {
			for (Instance instance : active()) {
				if (instance.disabled)
					continue;
				try {
					Hit hit = new Hit(instance.context, target, attack);
					damage = outgoing ? instance.behavior.outgoingDamage(hit, damage)
							: instance.behavior.incomingDamage(hit, damage);
				} catch (RuntimeException | LinkageError failure) {
					instance.disable(failure);
				}
			}
			return damage;
		}

		public void accepted(AttackAb attack, Entity target, int damage, boolean outgoing) {
			for (Instance instance : active()) {
				Hit hit = new Hit(instance.context, target, attack);
				instance.call(behavior -> {
					if (outgoing)
						behavior.onHit(hit, damage);
					else
						behavior.onDamaged(hit, damage);
				});
			}
		}

		public boolean allowInterruption(InterruptionType type, float distance) {
			boolean allowed = true;
			for (Instance instance : active()) {
				if (instance.disabled)
					continue;
				try {
					if (!instance.behavior.allowInterruption(new Interruption(instance.context, type, distance)))
						allowed = false;
				} catch (RuntimeException | LinkageError failure) {
					instance.disable(failure);
				}
			}
			return allowed;
		}

		public void draw(FakeGraphics graphics, float x, float y, float scale) {
			Instance[] snapshot = visuals;
			if (snapshot.length == 0)
				return;
			Visual visual = new Visual(graphics, x, y, scale);
			for (Instance instance : snapshot)
				instance.call(behavior -> behavior.onRender(visual));
		}

		private void updateVisuals() {
			visuals = active().toArray(new Instance[0]);
		}

		private List<Instance> active() {
			if (effects.isEmpty())
				return abilities;
			List<Instance> snapshot = new ArrayList<>(abilities);
			snapshot.addAll(effects);
			return snapshot;
		}

		private Instance create(BattleExtension definition, Entity source, String configuration, int ticks) {
			try {
				Behavior behavior = Objects.requireNonNull(definition.create(new Settings(definition.parameters(), configuration)));
				return new Instance(new Context(owner, source, definition.id()), behavior, ticks);
			} catch (RuntimeException | LinkageError failure) {
				System.err.println("BCU extension failed to start: " + definition.id() + ": " + failure);
				return null;
			}
		}
	}

	private static final class Instance {
		final Context context;
		final Behavior behavior;
		final boolean filtersProcs, preparesAttacks;
		int ticks;
		volatile boolean disabled;

		Instance(Context context, Behavior behavior, int ticks) {
			this.context = context;
			this.behavior = behavior;
			this.ticks = ticks;
			filtersProcs = overrides(behavior, "incomingProcs", Hit.class, Proc.class);
			preparesAttacks = overrides(behavior, "beforeAttack", Context.class, AttackDraft.class);
		}

		private static boolean overrides(Behavior behavior, String name, Class<?>... arguments) {
			try { return behavior.getClass().getMethod(name, arguments).getDeclaringClass() != Behavior.class; }
			catch (NoSuchMethodException failure) { throw new IllegalArgumentException(failure); }
		}

		void call(Consumer<Behavior> callback) {
			if (disabled)
				return;
			try {
				callback.accept(behavior);
			} catch (RuntimeException | LinkageError failure) {
				disable(failure);
			}
		}

		void end() { call(value -> value.onEnd(context)); }
		<T> T value(java.util.function.Function<Behavior, T> callback, T fallback) {
			if (disabled) return fallback;
			try { return callback.apply(behavior); }
			catch (RuntimeException | LinkageError failure) { disable(failure); return fallback; }
		}
		void disable(Throwable failure) {
			disabled = true;
			System.err.println("BCU extension disabled for this entity: " + context.id + ": " + failure);
		}
	}
}
