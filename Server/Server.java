// Ruta: Server/Server.java
package Server;

import java.io.*;
import java.net.*;

public final class Server {
    // --- Singleton ---
    private static volatile Server instance;
    private ServerSocket serverSocket;
    private final int port = 5000;

    private Server() { }

    public static Server getInstance() {
        if (instance == null) {
            synchronized (Server.class) {
                if (instance == null) instance = new Server();
            }
        }
        return instance;
    }
    // --- /Singleton ---

    public void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) return; // ya iniciado
        serverSocket = new ServerSocket(port);
        System.out.println("[JAVA] Servidor escuchando en puerto " + port + " ...");

        // Versión mínima: acepta 1 cliente, recibe 1 línea y responde
        try (Socket socket = serverSocket.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {

            System.out.println("[JAVA] Cliente conectado: " + socket.getInetAddress());
            String msg = in.readLine(); // requiere '\n' del lado C
            System.out.println("[JAVA] Recibido: " + msg);

            String respuesta = "OK_FROM_JAVA: " + msg + "\n";
            out.write(respuesta);
            out.flush();
            System.out.println("[JAVA] Enviado: " + respuesta.trim());
        } finally {
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                System.out.println("[JAVA] Servidor detenido.");
            }
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        try {
            Server.getInstance().start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
