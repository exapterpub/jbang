///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.5
//DEPS info.picocli:picocli-shell-jline3:4.7.5
//DEPS org.jline:jline:3.26.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0

import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import picocli.shell.jline3.PicocliJLineCompleter;
import org.jline.reader.*;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.*;

import javax.swing.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Base64;

@Command(name = "menu", mixinStandardHelpOptions = true, description = "Dynamic CLI/GUI Menu App with secret commands")
public class MenuApp implements Runnable {

	static CommandLine cli;
	static LineReader reader;
	static Terminal terminal;
	static JFrame frame;
	static JMenuBar bar;
	static boolean guiMode = false;

	public static final Path LOG_FILE = Paths.get("menuapp.log");

	public static void main(String[] args) throws Exception {

		for (String a : args)
			if ("--gui".equalsIgnoreCase(a))
				guiMode = true;

		if (guiMode)
			SwingUtilities.invokeLater(() -> startGui());
		else
			startCli(args);
	}

	public void run() {
		cli.usage(System.out);
	}

	// ================= CLI =================

	static void startCli(String[] args) throws Exception {
		rebuildCli();

		if (args.length > 0 && !"--gui".equalsIgnoreCase(args[0]))
			cli.execute(args);

		MenuLoader.watch("menus.json", MenuApp::rebuildCli);
		repl();
	}

	static void rebuildCli() {
		try {
			MenuConfig cfg = MenuLoader.load("menus.json");

			CommandSpec root = CommandSpec.forAnnotatedObject(new MenuApp());

			root.addSubcommand("log",
					CommandSpec.forAnnotatedObject(new LogCmd()));
			root.addSubcommand("help-all",
					CommandSpec.forAnnotatedObject(new HelpAllCmd()));
			root.addSubcommand("exit",
					CommandSpec.forAnnotatedObject(new ExitCmd()));

			for (MenuNode node : cfg.menus)
				root.addSubcommand(
						node.name.toLowerCase(),
						buildNode(node));

			cli = new CommandLine(root);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static void repl() throws Exception {

		terminal = TerminalBuilder.builder().system(true).build();

		reader = LineReaderBuilder.builder()
			.terminal(terminal)
			.completer(new PicocliJLineCompleter(cli.getCommandSpec()))
			.build();

		while (true) {
			String line;
			try {
				line = reader.readLine("menu> ");
			} catch (UserInterruptException e) {
				continue;
			} catch (EndOfFileException e) {
				break;
			}

			if (line == null || line.trim().isEmpty())
				continue;

			cli.execute(line.split("\\s+"));
		}
	}

	// ================= GUI =================

	static void startGui() {

		frame = new JFrame("Menu App");
		bar = new JMenuBar();
		frame.setJMenuBar(bar);

		rebuildGui();
		MenuLoader.watch("menus.json", MenuApp::rebuildGui);

		frame.setSize(600, 400);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	static void rebuildGui() {
		try {
			bar.removeAll();
			MenuConfig cfg = MenuLoader.load("menus.json");

			for (MenuNode node : cfg.menus)
				bar.add(buildMenu(node));

			bar.add(buildSystemMenu());
			bar.revalidate();
			bar.repaint();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	static JMenu buildMenu(MenuNode node) {
		JMenu menu = new JMenu(node.name);

		if (node.children != null)
			for (MenuNode child : node.children)
				if (child.children != null)
					menu.add(buildMenu(child));
				else
					menu.add(buildItem(child));

		return menu;
	}

	static JMenuItem buildItem(MenuNode node) {

		JMenuItem item = new JMenuItem(node.name);

		item.addActionListener(e -> MenuExecutor.run(node, new String[0]));

		return item;
	}

	static JMenu buildSystemMenu() {

		JMenu menu = new JMenu("System");

		JMenuItem view = new JMenuItem("View Log");
		view.addActionListener(e -> {
			try {
				JTextArea area = new JTextArea(
						Files.readString(LOG_FILE));
				JOptionPane.showMessageDialog(
						frame,
						new JScrollPane(area));
			} catch (Exception ex) {
				JOptionPane.showMessageDialog(
						frame,
						"No log yet.");
			}
		});

		menu.add(view);
		return menu;
	}

	// ================= DYNAMIC BUILD =================

	@Command
	static class DynamicLeaf implements Runnable {

		private final MenuNode node;

		@Parameters(arity = "0..*")
		private String[] args = new String[0];

		DynamicLeaf(MenuNode node) {
			this.node = node;
		}

		public void run() {
			MenuExecutor.run(node, args);
		}
	}

	static CommandSpec buildNode(MenuNode node) {

		boolean isLeaf = node.command != null &&
				(node.children == null || node.children.isEmpty());

		if (isLeaf) {
			DynamicLeaf leaf = new DynamicLeaf(node);
			CommandSpec spec = CommandSpec.forAnnotatedObject(leaf);
			spec.name(node.name.toLowerCase());
			spec.usageMessage().description(node.description);
			return spec;
		}

		CommandSpec spec = CommandSpec.create();
		spec.name(node.name.toLowerCase());
		spec.usageMessage().description(node.description);

		if (node.children != null)
			for (MenuNode child : node.children)
				spec.addSubcommand(
						child.name.toLowerCase(),
						buildNode(child));

		return spec;
	}

	// ================= HELP =================

	@Command(name = "help-all")
	static class HelpAllCmd implements Runnable {
		public void run() {
			printFilteredHelp(cli.getCommandSpec(), 0);
		}
	}

	static void printFilteredHelp(CommandSpec spec, int indent) {

		Object userObj = spec.userObject();

		if (userObj instanceof DynamicLeaf) {
			DynamicLeaf leaf = (DynamicLeaf) userObj;
			if (leaf.node.secret)
				return;
		}

		if (spec.name() != null)
			System.out.printf("%" + indent + "s%s%n",
					"", spec.name());

		for (CommandLine sub : spec.subcommands().values())
			printFilteredHelp(
					sub.getCommandSpec(),
					indent + 2);
	}

	// ================= BUILT-IN =================

	@Command(name = "log")
	static class LogCmd implements Runnable {
		public void run() {
			try {
				System.out.println(
						Files.readString(LOG_FILE));
			} catch (Exception e) {
				System.out.println("No log yet.");
			}
		}
	}

	@Command(name = "exit")
	static class ExitCmd implements Runnable {
		public void run() {
			System.out.println("Bye 👋");
			System.exit(0);
		}
	}

	// ================= PASSWORD =================

	static String promptPassword() throws Exception {

		// CLI mode → masked in terminal
		if (!guiMode && reader != null) {
			return reader.readLine("Enter passphrase: ", '*');
		}

		// GUI mode → custom dialog with masked input and guaranteed focus
		final JPasswordField pf = new JPasswordField(20);
		final String[] result = new String[1]; // to capture password from inner class

		JDialog dialog = new JDialog(frame, "Secret Command", true);
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(new JLabel("Enter passphrase:"));
		panel.add(pf);

		JButton okBtn = new JButton("OK");
		JButton cancelBtn = new JButton("Cancel");

		JPanel btnPanel = new JPanel();
		btnPanel.add(okBtn);
		btnPanel.add(cancelBtn);
		panel.add(btnPanel);

		dialog.setContentPane(panel);
		dialog.pack();
		dialog.setLocationRelativeTo(frame);

		okBtn.addActionListener(e -> {
			result[0] = new String(pf.getPassword());
			dialog.dispose();
		});

		cancelBtn.addActionListener(e -> {
			result[0] = null;
			dialog.dispose();
		});

		// Request focus on password field after dialog is shown
		dialog.addWindowListener(new java.awt.event.WindowAdapter() {
			public void windowOpened(java.awt.event.WindowEvent e) {
				pf.requestFocusInWindow();
			}
		});

		dialog.setVisible(true);

		// Clear password from field for security
		char[] pwd = pf.getPassword();
		if (pwd != null)
			java.util.Arrays.fill(pwd, '\0');

		return result[0];
	}

	// ================= EXECUTION =================

	static class MenuExecutor {

		static void run(MenuNode node,
				String[] args) {

			try {
				String cmd;

				if (node.secret) {

					String pass = promptPassword();

					if (pass == null || pass.isEmpty()) {
						log("Secret cancelled");
						return;
					}

					cmd = decryptAES(
							resolveSecret(node.command),
							pass);

				} else {
					cmd = CommandResolver
						.resolve(node.command, args);
				}

				if (cmd == null) {
					log("No command configured");
					return;
				}

				// remove unconditional logging
				if (!node.secret) {
					log("EXEC: " + cmd);
				}

				ProcessBuilder pb;

				if (System.getProperty("os.name")
					.toLowerCase()
					.contains("win"))
					pb = new ProcessBuilder("cmd", "/c", cmd);
				else
					pb = new ProcessBuilder("sh", "-c", cmd);

				pb.inheritIO();
				pb.start().waitFor();

			} catch (Exception e) {
				log("ERROR: " + e.getMessage());
			}
		}

		static String resolveSecret(Object spec) {
			if (spec instanceof String)
				return (String) spec;

			if (spec instanceof Map) {
				Map<?, ?> map = (Map<?, ?>) spec;
				String os = CommandResolver.detectOS();
				if (map.containsKey(os))
					return map.get(os).toString();
			}
			return null;
		}

		static String decryptAES(
				String cipherText,
				String passphrase)
				throws Exception {

			byte[] key = MessageDigest
				.getInstance("SHA-256")
				.digest(passphrase.getBytes("UTF-8"));

			SecretKeySpec secretKey = new SecretKeySpec(key, "AES");

			Cipher cipher = Cipher.getInstance("AES");

			cipher.init(
					Cipher.DECRYPT_MODE,
					secretKey);

			byte[] decoded = Base64.getDecoder().decode(cipherText);

			return new String(
					cipher.doFinal(decoded),
					"UTF-8");
		}
	}

	static synchronized void log(String msg) {
		try {
			Files.writeString(LOG_FILE,
					"[" + LocalDateTime.now() + "] "
							+ msg + "\n",
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	static class MenuLoader {
		static com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

		static MenuConfig load(String file) throws Exception {
			return mapper.readValue(
					Files.newBufferedReader(Paths.get(file)),
					MenuConfig.class);
		}

		static void watch(String file, Runnable reload) {
			new Thread(() -> {
				try {
					WatchService ws = FileSystems.getDefault()
						.newWatchService();
					Path path = Paths.get(file)
						.toAbsolutePath()
						.getParent();
					path.register(ws,
							StandardWatchEventKinds.ENTRY_MODIFY);
					while (true) {
						WatchKey key = ws.take();
						for (WatchEvent<?> ev : key.pollEvents())
							if (ev.context()
								.toString()
								.equals(file))
								reload.run();
						key.reset();
					}
				} catch (Exception e) {
					log("Watcher error: "
							+ e.getMessage());
				}
			}).start();
		}
	}

	static class MenuConfig {
		public List<MenuNode> menus;
	}

	static class MenuNode {
		public String name, description, shortcut;
		public Object command;
		public boolean secret = false;
		public List<MenuNode> children;
	}

	static class CommandResolver {

		static String resolve(Object spec,
				String[] args) {

			if (spec instanceof String)
				return ((String) spec)
					.replace("{args}",
							String.join(" ", args));

			if (spec instanceof Map) {
				Map<?, ?> map = (Map<?, ?>) spec;
				String os = detectOS();
				if (map.containsKey(os))
					return map.get(os)
						.toString()
						.replace("{args}",
								String.join(" ", args));
			}
			return null;
		}

		static String detectOS() {
			String os = System.getProperty("os.name")
				.toLowerCase();
			if (os.contains("win"))
				return "windows";
			if (os.contains("mac"))
				return "mac";
			return "linux";
		}
	}
}
