import common.battle.attack.AttackAb;
import common.battle.data.CustomEnemy;
import common.battle.entity.Entity;
import common.battle.extension.BattleExtension;
import common.battle.extension.BattleExtensions;
import common.pack.UserProfile;
import common.util.Data;
import extension.DesktopExtensions;
import java.nio.file.Paths;

public class PluginCompatibilitySmoke {
    public static void main(String[] args) throws Exception {
        DesktopExtensions.load(Paths.get(args[0]));
        CombatApiSmoke.check(BattleExtensions.definitions().size() == 2, "Anchor and documented SecondWind compile with bundled compiler");
        UserProfile.profile();
        common.CommonStatic.ctx = new common.io.assets.Admin.AdminContext();
        CustomEnemy sourceData = new CustomEnemy();
        sourceData.extensionSettings().put("bcu.example.anchor", "{\"seconds\":0.1}");
        CombatApiSmoke.TestEntity source = new CombatApiSmoke.TestEntity(sourceData);
        CombatApiSmoke.TestEntity target = new CombatApiSmoke.TestEntity(new CustomEnemy());
        source.added(-1, 0);
        target.added(1, 100);
        CombatApiSmoke.check(target.tryInterrupt(Data.INT_KB, 25), "KB initially accepted");
        AttackAb hit = CombatApiSmoke.attack(source, 6000);
        CombatApiSmoke.runtime(source).modify(hit, target, 6000, true);
        for (int type : new int[] {Data.INT_KB, Data.INT_HB, Data.INT_WARP, Data.INT_ASS, Data.INT_SW})
            CombatApiSmoke.check(!target.tryInterrupt(type, 50), "Anchor blocks displacement type " + type);
        Object kb = CombatApiSmoke.field(Entity.class, "kb").get(target);
        CombatApiSmoke.check(CombatApiSmoke.field(kb.getClass(), "tempKBtype").getInt(kb) == -1, "Anchor clears previously queued displacement");
        target.queueExtensionDamage(6000);
        target.postUpdate();
        CombatApiSmoke.check(target.health == 4000 && target.pos == 100, "Anchor preserves HP damage");
        BattleExtensions.Runtime runtime = CombatApiSmoke.runtime(target);
        for (int i = 0; i < 3; i++) runtime.tick();
        CombatApiSmoke.check(target.tryInterrupt(Data.INT_WARP, 10), "Anchor expires normally");

        CustomEnemy second = new CustomEnemy();
        second.extensionSettings().put("example.second_wind", "{\"lives\":1,\"hp\":30}");
        CombatApiSmoke.TestEntity phoenix = new CombatApiSmoke.TestEntity(second);
        phoenix.added(1, 100);
        phoenix.applyBattleEffect(phoenix, "test.stationary", new BattleExtension.Behavior() {
            public boolean allowInterruption(BattleExtension.Interruption event) { return false; }
        }, 100, BattleExtension.Stacking.REPLACE);
        phoenix.queueExtensionDamage(10000);
        phoenix.postUpdate();
        CombatApiSmoke.check(phoenix.health == 3000 && CombatApiSmoke.runtime(phoenix).movement(2) == 3,
                "documented plugin revives with configured HP and temporary haste");
        CombatApiSmoke.check(CombatApiSmoke.runtime(phoenix).reviveHealth() == 0, "documented plugin uses one life");
        System.out.println("PASS: bundled Java compiler, old Anchor plugin and new documented SecondWind plugin");
    }
}
