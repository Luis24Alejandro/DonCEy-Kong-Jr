// Ruta: Server/ClientHandler.java
package Server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Server server;
    private BufferedReader in;
    private BufferedWriter out;

    public ClientHandler(Socket socket, Server server) {
        this.socket = socket;
        this.server = server;
        try {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        try {
            sendLine("WELCOME\n");

            String line;
            socket.setSoTimeout(20000); // 20 s de inactividad máx. 
            while ((line = in.readLine()) != null) {
                System.out.println("[JAVA] <- " + line);

                if (line.startsWith("JOIN ")) {
                    server.onJoin(this, line.substring(5).trim());
                } else if (line.startsWith("INPUT ")) {
                    // INPUT <seq> <dx> <dy>
                    String[] t = line.split("\\s+");
                    if (t.length >= 4) {
                        int seq = Integer.parseInt(t[1]);
                        int dx  = Integer.parseInt(t[2]);
                        int dy  = Integer.parseInt(t[3]);
                        server.onInput(this, seq, dx, dy);
                    } else {
                        sendLine("ERR BAD_INPUT\n");
                    }
                } else if (line.startsWith("SPECTATE ")) {
                    try {
                        int pid = Integer.parseInt(line.substring(9).trim());
                        server.onSpectate(this, pid);
                    } catch (NumberFormatException e) {
                        sendLine("ERR BAD_SPECTATE\n");
                    }
                } else if (line.equalsIgnoreCase("PING")) {
                    sendLine("PONG\n");
                } else if (line.equalsIgnoreCase("QUIT")) {
                    sendLine("BYE\n");
                    break;
                } else {
                    sendLine("ERR UNKNOWN\n");
                }
            }
        } catch (IOException e) {
            System.out.println("[JAVA] Cliente desconectado: " + e.getMessage());
        } finally {
            close();
            server.removeClient(this);
        }
    }

    public synchronized void sendLine(String s) {
        try {
            out.write(s);
            out.flush();
        } catch (IOException ignored) {}
    }

    public void close() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}
