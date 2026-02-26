///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.github.kwhat:jnativehook:2.2.2

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MouseCapture implements NativeKeyListener {

	private JFrame frame;
	private DefaultTableModel tableModel;
	private JTable table;
	private JLabel liveCoords;

	private boolean ctrlPressed = false;
	private boolean shiftPressed = false;

	public static void main(String[] args) {
		SwingUtilities.invokeLater(() -> new MouseCapture(args));
	}

	public MouseCapture(String[] args) {
		disableNativeHookLogging();
		registerGlobalHook();
		buildUI();
		startLiveTracker();

		if (args != null && args.length > 0) {
			loadNamesFile(new File(args[0]));
		}
	}

	private void buildUI() {
		frame = new JFrame("Mouse Capture");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(800, 320);
		frame.setLocationRelativeTo(null);
		frame.setLayout(new BorderLayout(5, 5));

		tableModel = new DefaultTableModel(new String[] { "Name", "X", "Y" }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return true; // allow manual edits too
			}
		};

		table = new JTable(tableModel);

		JScrollPane scrollPane = new JScrollPane(table);

		liveCoords = new JLabel("Mouse: x=0 y=0");
		liveCoords.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		JButton deleteBtn = new JButton("Delete Selected");
		deleteBtn.addActionListener(e -> deleteSelectedRows());

		JButton saveBtn = new JButton("Save CSV");
		saveBtn.addActionListener(this::saveCSV);

		JButton loadBtn = new JButton("Load CSV");
		loadBtn.addActionListener(this::loadCSV);

		JToggleButton alwaysOnTop = new JToggleButton("Always On Top");
		alwaysOnTop.addActionListener(e -> frame.setAlwaysOnTop(alwaysOnTop.isSelected()));

		JPanel controls = new JPanel();
		controls.add(deleteBtn);
		controls.add(loadBtn);
		controls.add(alwaysOnTop);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(controls, BorderLayout.CENTER);
		bottom.add(saveBtn, BorderLayout.SOUTH);

		frame.add(liveCoords, BorderLayout.NORTH);
		frame.add(scrollPane, BorderLayout.CENTER);
		frame.add(bottom, BorderLayout.SOUTH);

		frame.setVisible(true);

		enableTableUnselectOnOutsideClick();
	}

	private void startLiveTracker() {
		new Timer(100, e -> {
			PointerInfo info = MouseInfo.getPointerInfo();
			if (info != null) {
				Point p = info.getLocation();
				liveCoords.setText("Mouse: x=" + p.x + " y=" + p.y);
			}
		}).start();
	}

	private void captureMousePosition() {
		PointerInfo info = MouseInfo.getPointerInfo();
		if (info == null)
			return;

		Point p = info.getLocation();
		int selectedRow = table.getSelectedRow();

		if (selectedRow >= 0) {
			Object existingX = tableModel.getValueAt(selectedRow, 1);
			Object existingY = tableModel.getValueAt(selectedRow, 2);

			boolean hasCoords = existingX != null && !existingX.toString().isBlank() && existingY != null && !existingY.toString().isBlank();

			if (hasCoords) {
				int confirm = JOptionPane.showConfirmDialog(frame, "Selected row already has coordinates.\nOverwrite them?", "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

				if (confirm != JOptionPane.YES_OPTION)
					return;
			}

			tableModel.setValueAt(p.x, selectedRow, 1);
			tableModel.setValueAt(p.y, selectedRow, 2);

		} else {
			String name = JOptionPane.showInputDialog(frame, "Name for (" + p.x + ", " + p.y + "):", "New Coordinate", JOptionPane.PLAIN_MESSAGE);

			if (name != null && !name.trim().isEmpty()) {
				tableModel.addRow(new Object[] { name.trim(), p.x, p.y });
			}
		}
	}

	/*
	private void enableTableUnselectOnOutsideClick() {
	Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
	    if (!(event instanceof java.awt.event.MouseEvent)) return;
	
	    java.awt.event.MouseEvent me = (java.awt.event.MouseEvent) event;
	    Component clicked = SwingUtilities.getDeepestComponentAt(
	            frame, me.getXOnScreen() - frame.getLocationOnScreen().x,
	            me.getYOnScreen() - frame.getLocationOnScreen().y
	    );
	
	    if (clicked == null) {
	        table.clearSelection();
	        return;
	    }
	
	    if (!SwingUtilities.isDescendingFrom(clicked, table)) {
	        table.clearSelection();
	    }
	}, AWTEvent.MOUSE_EVENT_MASK);
	}
	*/

	private void enableTableUnselectOnOutsideClick() {
		Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
			if (!(event instanceof java.awt.event.MouseEvent))
				return;

			java.awt.event.MouseEvent me = (java.awt.event.MouseEvent) event;

			// Only react to actual click press
			if (me.getID() != java.awt.event.MouseEvent.MOUSE_PRESSED)
				return;

			// Ignore clicks outside our window
			if (!SwingUtilities.isDescendingFrom(me.getComponent(), frame))
				return;

			// If click is NOT inside table â†’ clear selection
			if (!SwingUtilities.isDescendingFrom(me.getComponent(), table)) {
				table.clearSelection();
			}
		}, AWTEvent.MOUSE_EVENT_MASK);
	}

	private void deleteSelectedRows() {
		int[] rows = table.getSelectedRows();
		for (int i = rows.length - 1; i >= 0; i--) {
			tableModel.removeRow(rows[i]);
		}
	}

	private void saveCSV(ActionEvent e) {
		if (tableModel.getRowCount() == 0) {
			JOptionPane.showMessageDialog(frame, "No data to save.");
			return;
		}

		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save CSV");

		if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION)
			return;

		File file = chooser.getSelectedFile();

		try (FileWriter writer = new FileWriter(file)) {
			for (int i = 0; i < tableModel.getRowCount(); i++) {
				writer.write(tableModel.getValueAt(i, 0) + "," + tableModel.getValueAt(i, 1) + "," + tableModel.getValueAt(i, 2) + "\n");
			}
			JOptionPane.showMessageDialog(frame, "Saved!");
		} catch (IOException ex) {
			JOptionPane.showMessageDialog(frame, "Error: " + ex.getMessage());
		}
	}

	private void loadCSV(ActionEvent e) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Load Names / Coordinates");

		if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION)
			return;

		loadNamesFile(chooser.getSelectedFile());
	}

	private void loadNamesFile(File file) {
		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			tableModel.setRowCount(0);

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty())
					continue;

				String[] parts = line.split(",");

				String name = parts[0].trim();
				String x = "";
				String y = "";

				if (parts.length >= 3) {
					x = parts[1].trim();
					y = parts[2].trim();
				}

				tableModel.addRow(new Object[] { name, x, y });
			}

			frame.setTitle("Mouse Capture â€” " + file.getName());
			JOptionPane.showMessageDialog(frame, "Loaded: " + file.getName());

		} catch (Exception ex) {
			JOptionPane.showMessageDialog(frame, "Error loading file: " + ex.getMessage());
		}
	}

	private void registerGlobalHook() {
		try {
			GlobalScreen.registerNativeHook();
		} catch (NativeHookException e) {
			JOptionPane.showMessageDialog(null, "Failed to register global hook.");
			System.exit(1);
		}
		GlobalScreen.addNativeKeyListener(this);
	}

	private void disableNativeHookLogging() {
		Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
		logger.setLevel(Level.OFF);
		logger.setUseParentHandlers(false);
	}

	@Override
	public void nativeKeyPressed(NativeKeyEvent e) {
		if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL)
			ctrlPressed = true;
		if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT)
			shiftPressed = true;

		if (ctrlPressed && shiftPressed && e.getKeyCode() == NativeKeyEvent.VC_X) {
			SwingUtilities.invokeLater(this::captureMousePosition);
		}
	}

	@Override
	public void nativeKeyReleased(NativeKeyEvent e) {
		if (e.getKeyCode() == NativeKeyEvent.VC_CONTROL)
			ctrlPressed = false;
		if (e.getKeyCode() == NativeKeyEvent.VC_SHIFT)
			shiftPressed = false;
	}

	@Override
	public void nativeKeyTyped(NativeKeyEvent e) {
	}
}
