//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.awt.*;

public class AwsEc2ToSshConfig {

    private static final String DEFAULT_USER = "ubuntu";
    private static String identityFile;

    public static void main(String[] args) {

        if (args.length == 0 || args[0].isBlank()) {
            System.err.println("Usage: jbang AwsEc2ToSshConfig.java <IDENTITY_FILE>");
            System.exit(1);
        }

        identityFile = args[0];

        SwingUtilities.invokeLater(AwsEc2ToSshConfig::createUI);
    }

    private static void createUI() {
        JFrame frame = new JFrame("EC2 JSON → SSH Config");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 750);

        JTextArea inputArea = new JTextArea();
        JTextArea outputArea = new JTextArea();
        outputArea.setEditable(false);

        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JScrollPane inputScroll = new JScrollPane(inputArea);
        JScrollPane outputScroll = new JScrollPane(outputArea);

        JButton convertButton = new JButton("Convert");

        convertButton.addActionListener(e -> {
            try {
                String result = convertJsonToSsh(inputArea.getText());
                outputArea.setText(result);
            } catch (Exception ex) {
                outputArea.setText("Error:\n" + ex.getMessage());
            }
        });

        JPanel textPanel = new JPanel(new GridLayout(2, 1, 8, 8));
        textPanel.add(inputScroll);
        textPanel.add(outputScroll);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(convertButton);

        frame.setLayout(new BorderLayout(8, 8));
        frame.add(textPanel, BorderLayout.CENTER);
        frame.add(buttonPanel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static String convertJsonToSsh(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        StringBuilder sb = new StringBuilder();
        processNode(root, sb);
        return sb.toString();
    }

    private static void processNode(JsonNode node, StringBuilder sb) {
        if (node == null) return;

        if (node.isArray()) {
            for (JsonNode item : node) {
                processNode(item, sb);
            }
            return;
        }

        if (node.isObject()) {
            String id = text(node, "InstanceId");
            String name = text(node, "Name");
            String dns = text(node, "PublicDNS");
            String user = text(node, "User");

            if (id != null && name != null && dns != null) {
                if (user == null || user.isBlank()) {
                    user = DEFAULT_USER;
                }

                sb.append("Host ").append(name).append("_").append(id).append("\n");
                sb.append("    HostName ").append(dns).append("\n");
                sb.append("    IdentityFile ").append(identityFile).append("\n");
                sb.append("    User ").append(user).append("\n\n");
            }

            node.fields().forEachRemaining(entry -> processNode(entry.getValue(), sb));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
