import common.battle.extension.BattleExtension;

import java.util.Collections;
import java.util.List;

public class Anchor implements BattleExtension {
    public String id() { return "bcu.example.anchor"; }
    public String name() { return "Anchor (Ghim chặt)"; }
    public String description() {
        return "Ghim mục tiêu khi đánh trúng. Chặn Knockback, Warp và lùi do mất ngưỡng HP.";
    }

    public List<Parameter> parameters() {
        return Collections.singletonList(Parameter.decimal("seconds", "Thời gian (giây)", 3, 0.1, 60));
    }

    public Behavior create(Settings settings) {
        int ticks = (int) Math.round(settings.decimal("seconds") * 30);
        return new Behavior() {
            @Override
            public int outgoingDamage(Hit hit, int damage) {
                // Apply before BCU processes this hit's own knockback/warp effects.
                if (!hit.target.isBase() && hit.context.self.dire != hit.target.dire && hit.attack.atk >= 0) {
                    Behavior anchored = new Behavior() {
                        @Override
                        public boolean allowInterruption(Interruption interruption) {
                            return false;
                        }
                    };
                    hit.context.applyEffect(hit.target, "bcu.example.anchor.active",
                            anchored, ticks, Stacking.REFRESH);
                    hit.context.cancelDisplacement(hit.target);
                }
                return damage;
            }
        };
    }
}
