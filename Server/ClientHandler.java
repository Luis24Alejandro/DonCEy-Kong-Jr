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
            // Handshake simple (opcional)
            sendLine("WELCOME\n");

            String line;
            // Bucle principal: el servidor SIEMPRE recibe hasta que el cliente cierre o envíe QUIT
            while ((line = in.readLine()) != null) {
                System.out.println("[JAVA] De " + socket.getRemoteSocketAddress() + ": " + line);

                // Protocolo mínimo por comandos
                if (line.equalsIgnoreCase("PING")) {
                    sendLine("PONG\n");
                } else if (line.startsWith("MOVE ")) {
                    // Ejemplo: MOVE playerId dx dy
                    // Aquí actualizarías estado del juego y avisarías a los demás:
                    server.broadcast("MOVED " + line.substring(5) + "\n");
                } else if (line.equalsIgnoreCase("QUIT")) {
                    sendLine("BYE\n");
                    break;
                } else {
                    // Eco por defecto mientras definimos protocolo
                    sendLine("OK_FROM_JAVA: " + line + "\n");
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
