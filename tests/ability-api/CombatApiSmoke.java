import common.battle.StageBasis;
import common.battle.attack.*;
import common.battle.data.CustomEnemy;
import common.battle.entity.Entity;
import common.battle.extension.BattleExtension;
import common.battle.extension.BattleExtension.*;
import common.battle.extension.BattleExtensions;
import common.pack.UserProfile;
import common.util.Data;
import java.lang.reflect.*;
import java.util.*;

/** Runs against the shipped JAR on Java 8; no downloaded game assets required. */
public final class CombatApiSmoke {
    static Context context;
    static int revivals, hpEvents, failedCalls;
    static boolean resurrect = true, cancelAttack;

    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    static BattleExtensions.Runtime runtime(Entity entity) throws Exception {
        return (BattleExtensions.Runtime) field(Entity.class, "extensions").get(entity);
    }
    static BattleExtension definition(String id, Behavior behavior) {
        return new BattleExtension() {
            public String id() { return id; }
            public String name() { return id; }
            public Behavior create(Settings settings) { return behavior; }
        };
    }

    public static void main(String[] args) throws Exception {
        UserProfile.profile();
        common.CommonStatic.ctx = new common.io.assets.Admin.AdminContext();
        Map<String, BattleExtension> entries = new LinkedHashMap<>();
        entries.put("test.combat", definition("test.combat", new Behavior() {
            public void onStart(Context c) { context = c; }
            public boolean allowHit(Hit hit) { return hit.attack.atk != 777; }
            public boolean allowInterruption(Interruption event) { return false; }
            public boolean allowEffect(Context c, Entity source, String id) { return !id.equals("test.blocked"); }
            public void incomingProcs(Hit hit, Data.Proc proc) { proc.STOP.clear(); proc.SLOW.time /= 2; }
            public void beforeAttack(Context c, AttackDraft draft) {
                draft.damage *= 2;
                draft.end += 10;
                draft.procs.STOP.time = 30;
                draft.cancelled = cancelAttack;
            }
            public float movement(Context c, float distance) { return distance * 2; }
            public long healthChange(Context c, long delta) { return delta < 0 && delta > -9000 ? delta / 2 : delta; }
            public void onHealthChanged(Context c, long old) { hpEvents++; }
            public long reviveHealth(Context c) {
                if (!resurrect) return 0;
                resurrect = false;
                return 2500;
            }
            public void onRevive(Context c) { revivals++; }
        }));
        BattleExtensions.install(entries);
        CustomEnemy data = new CustomEnemy();
        data.extensionSettings().put("test.combat", "{}");
        TestEntity entity = new TestEntity(data);
        entity.added(-1, 100);
        Context c = context;
        BattleExtensions.Runtime runtime = runtime(entity);

        check(!entity.damaged(attack(entity, 777)), "hit cancellation precedes native processing");
        check(!entity.applyBattleEffect(entity, "test.blocked", new Behavior(){}, 3, Stacking.STACK), "effect immunity");
        entity.status[Data.P_SLOW][0] = 30;
        entity.walk();
        check(entity.pos == 99.5f, "walking modifier wired to engine");
        check(c.statusTime(entity, Status.SLOW) == 30, "read status");
        c.clearStatus(entity, Status.SLOW);
        check(entity.status[Data.P_SLOW][0] == 0, "cleanse status");

        AttackAb original = attack(entity, 100);
        original.getProc().STOP.time = 40;
        original.getProc().SLOW.time = 80;
        Data.Proc filtered = runtime.incomingProcs(original);
        check(filtered.STOP.time == 0 && filtered.SLOW.time == 40, "selective status filtering");
        check(original.getProc().STOP.time == 40 && original.getProc().SLOW.time == 80, "other targets keep original procs");

        FakeModel model = new FakeModel(entity);
        AttackAb prepared = model.getAttack(0);
        check(prepared.atk == 200 && prepared.rawAtk == 200 && prepared.getProc().STOP.time == 30, "attack draft changes native constructor");
        cancelAttack = true;
        check(model.getAttack(0) == null, "cancel native attack");
        cancelAttack = false;

        entity.queueExtensionDamage(1000);
        entity.postUpdate();
        check(entity.health == 9500 && hpEvents == 1, "HP modifiers affect actual postUpdate");
        c.heal(10000);
        entity.postUpdate();
        check(entity.health == 10000, "healing capped at max HP");
        c.damage(10000);
        entity.postUpdate();
        check(entity.health == 2500 && revivals == 1 && entity.isExtensionActive(), "one-shot revival precedes kill accounting");
        check(field(Entity.class, "killCounted").getBoolean(entity) == false, "revival awards no kill");

        check(c.cooldown("pulse", 2) && !c.cooldown("pulse", 2), "cooldown consumed");
        runtime.tick();
        check(!c.cooldown("pulse", 2), "cooldown still active");
        runtime.tick();
        check(c.cooldown("pulse", 2) && c.age() == 2, "cooldown follows simulation ticks");

        entity.applyBattleEffect(entity, "test.bad", new Behavior() {
            public float movement(Context ignored, float distance) { failedCalls++; return Float.NaN; }
        }, 10, Stacking.STACK);
        check(runtime.movement(2) == 4 && runtime.movement(2) == 4 && failedCalls == 1, "bad modifier isolated and disabled once");
        entity.applyBattleEffect(entity, "test.badproc", new Behavior() {
            public void incomingProcs(Hit hit, Data.Proc proc) { proc.SLOW.time = 1; throw new IllegalStateException("test"); }
        }, 10, Stacking.STACK);
        check(runtime.incomingProcs(original).SLOW.time == 40, "failed proc mutation rolled back");
        entity.applyBattleEffect(entity, "test.badattack", new Behavior() {
            public void beforeAttack(Context ignored, AttackDraft draft) { draft.damage = 1; draft.start = Float.NaN; }
        }, 10, Stacking.STACK);
        check(model.getAttack(0).atk == 200, "invalid attack draft rolled back");

        int[] ends = {0};
        entity.applyBattleEffect(entity, "test.clean", new Behavior() {
            public void onEnd(Context ignored) { ends[0]++; }
        }, 1, Stacking.STACK);
        runtime.tick();
        runtime.tick();
        check(ends[0] == 1, "effect ends exactly once");
        check(!c.summon(null) && !c.summon(Data.Proc.blank().SUMMON), "missing summon safely rejected");

        queueAndTargets(entity, c);
        runtime.death();
        check(ends[0] == 1, "death does not end expired effects twice");
        System.out.println("PASS: hit/effect immunity, walking, HP/revival, attack creation, proc isolation, cooldowns, error isolation, attack queue and target selection");
    }

    // Allocate only a battle shell for queue/query tests; no stage/assets are loaded.
    static void queueAndTargets(TestEntity self, Context c) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        StageBasis battle = (StageBasis) unsafe.allocateInstance(StageBasis.class);
        List<AttackAb> active = new ArrayList<>(), pending = new ArrayList<>();
        field(StageBasis.class, "la").set(battle, active);
        field(StageBasis.class, "extensionAttacks").set(battle, pending);
        field(Entity.class, "basis").set(self, battle);
        TestEntity enemy = new TestEntity(new CustomEnemy());
        enemy.added(1, self.pos + 20);
        List<Entity> entities = new ArrayList<>(Arrays.asList(self, enemy));
        field(StageBasis.class, "le").set(battle, entities);
        field(StageBasis.class, "tempe").set(battle, new ArrayList<common.battle.entity.EntCont>());
        common.pack.Identifier<common.util.unit.AbEnemy> id = new common.pack.Identifier<>(
                common.pack.Identifier.DEF, common.util.unit.Enemy.class, 0);
        UserProfile.getBCData().enemies.add(new common.util.unit.Enemy(id, null, new CustomEnemy()));
        Data.Proc.SUMMON summon = Data.Proc.blank().SUMMON;
        summon.id = id;
        summon.time = 30;
        check(c.summon(summon) && battle.tempe.size() == 1 && summon.time == 30,
                "summon dispatch queues native model result and keeps caller configuration independent");
        check(c.targets(30, false).equals(Collections.singletonList(enemy)), "enemy query");
        check(c.targets(30, true).equals(Collections.singletonList(self)), "ally query");
        active.add(attack(self, 1));
        active.forEach(a -> check(c.attack(10, 0, 50, null), "queue attack from callback"));
        check(active.size() == 1 && pending.size() == 1, "new attacks do not mutate active iteration");
        Method flush = StageBasis.class.getDeclaredMethod("flushExtensionAttacks");
        flush.setAccessible(true);
        flush.invoke(battle);
        check(active.size() == 2 && pending.isEmpty(), "queued attacks enter next capture pass");
        for (int i = 0; i < 256; i++) battle.queueExtensionAttack(attack(self, 1));
        try { battle.queueExtensionAttack(attack(self, 1)); throw new AssertionError("queue limit"); }
        catch (IllegalStateException expected) { }
    }

    static AttackAb attack(Entity source, int damage) {
        return new AttackAb(source, new AtkModelAb(null) {
            public int getAbi() { return 0; }
            public int getDire() { return -1; }
            public float getPos() { return 0; }
        }, damage, Collections.emptyList(), 0, Data.Proc.blank(), 0, 10, null, 0, false, 1) {
            public void capture() {}
            public void excuse() {}
        };
    }
    static final class FakeModel extends AtkModelEntity {
        FakeModel(Entity entity) { super(entity, 1, 1); act[0] = -1; }
        protected int getAttack(int index, Data.Proc proc) { return 100; }
        public void summon(Data.Proc.SUMMON proc, Entity target, Object acs, int resist) {
            e.basis.tempe.add(new common.battle.entity.EntCont(target, proc.time));
            proc.time = 0;
        }
    }
    static final class TestEntity extends Entity {
        FakeModel model;
        TestEntity(CustomEnemy data) { super(null, data, null, 1, 1); model = new FakeModel(this); }
        public AtkModelEntity extensionAttackModel() { return model; }
        void walk() { updateMove(0); }
        public float getResistValue(AttackAb attack, String name, int value) { return 1; }
        protected int getDamage(AttackAb attack, int damage) { return damage; }
        protected void onLastBreathe() {}
        protected float getLim() { return 10000; }
        protected int traitType() { return 0; }
        public int touchable() { return Data.TCH_N; }
    }
}
