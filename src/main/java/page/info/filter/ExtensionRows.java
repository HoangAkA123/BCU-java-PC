package page.info.filter;

import com.google.gson.JsonObject;
import common.battle.data.CustomEntity;
import common.battle.extension.BattleExtension;
import common.battle.extension.BattleExtensions;
import extension.DesktopExtensions;

import javax.swing.*;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

final class ExtensionRows {
	private final AttList list;
	private final CustomEntity entity;
	private final int offset;
	private final List<BattleExtension> entries = new ArrayList<>();

	ExtensionRows(AttList list, Vector<String> names, CustomEntity entity, boolean enemy, boolean editable) {
		this.list = list;
		this.entity = entity;
		offset = names.size();
		for (BattleExtension extension : BattleExtensions.definitions()) {
			if (!BattleExtensions.supports(extension, enemy))
				continue;
			entries.add(extension);
			names.add(extension.name() + (extension.kind() == BattleExtension.Kind.EFFECT ? " [Effect]" : " [Ability]"));
		}

		if (entries.isEmpty())
			return;
		list.extensionRows = this;
		list.setToolTipText("Double-click a custom ability or effect to configure it.");
		if (editable)
			list.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					int index = list.locationToIndex(event.getPoint());
					if (event.getClickCount() == 2 && contains(index)
							&& list.getCellBounds(index, index).contains(event.getPoint()))
						configure(entries.get(index - offset));
				}
			});
	}

	boolean contains(int index) { return index >= offset && index < offset + entries.size(); }

	void decorate(JLabel label, int index) {
		BattleExtension extension = entries.get(index - offset);
		label.setIcon(DesktopExtensions.icon(extension.id()));
	}

	void selectSaved() {
		for (int index = 0; index < entries.size(); index++)
			if (entity.extensionSettings().containsKey(entries.get(index).id()))
				list.addSelectionInterval(offset + index, offset + index);
	}

	void saveSelection() {
		for (int index = 0; index < entries.size(); index++) {
			String id = entries.get(index).id();
			if (list.isSelectedIndex(offset + index))
				entity.extensionSettings().putIfAbsent(id, "{}");
			else
				entity.extensionSettings().remove(id);
		}
	}

	private void configure(BattleExtension definition) {
		List<BattleExtension.Parameter> parameters = definition.parameters();
		if (parameters.isEmpty()) {
			JOptionPane.showMessageDialog(list, definition.description(), definition.name(), JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		BattleExtension.Settings current = new BattleExtension.Settings(parameters, entity.extensionSettings().get(definition.id()));
		JPanel form = new JPanel(new GridLayout(0, 2, 8, 4));
		List<JTextField> fields = new ArrayList<>();
		for (BattleExtension.Parameter parameter : parameters) {
			JTextField field = new JTextField(current.text(parameter.key), 16);
			fields.add(field);
			form.add(new JLabel(parameter.label));
			form.add(field);
		}
		Object[] content = {definition.description(), new JScrollPane(form)};
		while (JOptionPane.showConfirmDialog(list, content, definition.name(),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
			try {
				JsonObject values = new JsonObject();
				for (int index = 0; index < parameters.size(); index++) {
					BattleExtension.Parameter parameter = parameters.get(index);
					values.addProperty(parameter.key, parameter.validate(fields.get(index).getText()));
				}
				entity.extensionSettings().put(definition.id(), values.toString());
				return;
			} catch (IllegalArgumentException invalid) {
				JOptionPane.showMessageDialog(list, invalid.getMessage(), definition.name(), JOptionPane.ERROR_MESSAGE);
			}
		}
	}
}
