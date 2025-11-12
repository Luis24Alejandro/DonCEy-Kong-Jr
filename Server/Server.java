// Ruta: Server/Server.java
package Server;

import Server.entities.*;
import Server.factory.*;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * Clase principal del servidor
 * <p>Esta clase implementa el patrón de diseño Singleton para asegurar
 * que solo exista una instancia del servidor en toda la aplicación.</p>
 * <p>Se encarga de manejar la lógica central del juego, la gestión de eventos
 * de entrada y la coordinación de todos los componentes del sistema.</p>
 * @author [Jose]
 */
public final class Server {
    /* ========= Eventos de input ========= */
    /**
     * Representa un evento de entrada (input) del jugador.
     * <p>Contiene la información necesaria para procesar una acción de movimiento
     * del cliente y su número de secuencia para reconciliación.</p>
     */
    static final class InputEvent {
        final int playerId, seq, dx, dy;
        InputEvent(int playerId, int seq, int dx, int dy) {
            this.playerId = playerId; this.seq = seq; this.dx = dx; this.dy = dy;
        }
    }

    /* ========= Singleton ========= */
    /**
     * La única instancia de la clase {@code Server} (patrón Singleton).
     */
    private static volatile Server instance;
    /**
     * Constructor privado para prevenir la instanciación externa.
     */
    private Server() {}
    /**
     * Obtiene la única instancia de la clase {@code Server}.
     *
     * <p>Utiliza el patrón Double-Checked Locking para garantizar la
     * seguridad de hilos al inicializar la instancia.</p>
     *
     * @return La única instancia disponible del servidor.
     */
    public static Server getInstance() {
        if (instance == null) {
            synchronized (Server.class) { if (instance == null) instance = new Server(); }
        }
        return instance;
    }

    /* ========= Sockets / pools ========= */
    /** El socket principal del servidor, usado para aceptar nuevas conexiones. */
    private ServerSocket serverSocket;
    /** El puerto TCP donde el servidor está escuchando conexiones. */
    private final int port = 5000;
    /** Pool de hilos para manejar la comunicación I/O con los {@link ClientHandler}. */
    private final ExecutorService pool = Executors.newCachedThreadPool();
    /** Lista de todos los manejadores de clientes conectados (jugadores y espectadores). */
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();

    /* ========= Estado básico ========= */
    /** Contador atómico para generar el siguiente ID único de jugador. */
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger tickSeq = new AtomicInteger(0);
    final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<>();
    final ConcurrentHashMap<ClientHandler, Integer> byClient = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<InputEvent> inputQueue = new ConcurrentLinkedQueue<>();

    /* ========= Jugadores vs espectadores ========= */
    /** Conjunto de {@link ClientHandler} que actualmente están actuando como jugadores. */
    private final Set<ClientHandler> playerClients =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> spectatorsByPlayer =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CopyOnWriteArrayList<ClientHandler>> waitingSpectatorsByPlayer =
            new ConcurrentHashMap<>();

    /* ========= Plantillas del Admin (Abstract Factory) ========= */
    /** Factoría utilizada para crear elementos del juego, siguiendo el patrón **Abstract Factory**. */
    private final GameElementFactory factory = new DefaultFactory();
    private final CopyOnWriteArrayList<Enemy> templateEnemies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Fruit> templateFruits  = new CopyOnWriteArrayList<>();

    /* ========= Sesiones por jugador ========= */
    /** Almacena la sesión de juego activa por cada ID de jugador. */
    private final ConcurrentHashMap<Integer, GameSession> sessions = new ConcurrentHashMap<>();

    /* ========= Mapa (ajústalo a tu tablero) ========= */
    /** Límite inferior de la coordenada Y para el tablero de juego. */
    private static final int MIN_Y = 0, MAX_Y = 10;
    private static final int MIN_X = 0, MAX_X = 10;

    /* ========= Loop ========= */
    /** Planificador de un solo hilo para ejecutar el {@link #tick()} a una velocidad constante. */
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

    /**
     * Inicializa el socket del servidor, lo enlaza al puerto y comienza a escuchar
     * conexiones.
     * <p>También inicia el *Game Loop* ({@link #tick()}) y el hilo de la consola
     * de administración.</p>
     * @throws IOException Si ocurre un error al abrir el socket del servidor.
     */
    public void start() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) return;

        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        System.out.println("[JAVA] Servidor escuchando en puerto " + port + " ...");

        ticker.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        new Thread(this::adminLoop, "AdminConsole").start();

        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                System.out.println("[JAVA] Cliente conectado: " + socket.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                pool.submit(handler);
            } catch (SocketException se) {
                System.out.println("[JAVA] Aceptación detenida: " + se.getMessage());
                break;
            }
        }
    }

    /* ========= TICK: procesa inputs, simula enemigos y notifica ========= */
    /**
     * El ciclo principal de simulación del juego (Game Loop).
     *
     * <p>Se ejecuta a una tasa fija (controlada por {@link #ticker}) y realiza
     * tres pasos principales:</p>
     * <ol>
     * <li>Procesar los inputs de la {@link #inputQueue} y actualizar la posición de los {@link Player}.</li>
     * <li>Simular los enemigos y chequear eventos de juego (colisiones, recoger frutas, meta).</li>
     * <li>Enviar el nuevo estado del juego a los jugadores y espectadores.</li>
     * </ol>
     */
    private void tick() {
        // 1) inputs → posiciones (con límites) + ack
        InputEvent ev;
        while ((ev = inputQueue.poll()) != null) {
            Player p = players.get(ev.playerId);
            if (p == null) continue;
            int nx = p.x + ev.dx, ny = p.y + ev.dy;
            if (nx < MIN_X) nx = MIN_X; if (nx > MAX_X) nx = MAX_X;
            if (ny < MIN_Y) ny = MIN_Y; if (ny > MAX_Y) ny = MAX_Y;
            p.x = nx; p.y = ny; p.lastAckSeq = Math.max(p.lastAckSeq, ev.seq);
        }

        // 2) simular enemigos por sesión y chequear eventos de juego
        for (var e : sessions.entrySet()) {
            int pid = e.getKey();
            GameSession s = e.getValue();
            Player p = players.get(pid);
            if (p == null) continue;

            // avanzar enemigos con “velocidad” de la ronda de ese jugador
            for (int step = 0; step < Math.max(1, s.enemySpeedSteps); step++) {
                for (Enemy enemy : s.enemies) enemy.tick(MIN_Y, MAX_Y);
            }

            // colisiones con enemigos → LOSE (juego termina para ese jugador)
            boolean hit = s.enemies.stream().anyMatch(en -> en.getX() == p.x && en.getY() == p.y);
            if (hit) {
                sendToPlayerAndSpectators(pid, "LOSE " + pid + " " + p.score + "\n");
                // cerrar sesión de ese jugador
                endPlayerSession(pid);
                continue;
            }

            // recoger frutas → +pts y borrar fruta
            Iterator<Fruit> it = s.fruits.iterator();
            while (it.hasNext()) {
                Fruit f = it.next();
                if (f.getX() == p.x && f.getY() == p.y) {
                    p.score += f.getPoints();
                    it.remove();
                    sendToPlayerAndSpectators(pid, "SCORE " + pid + " " + p.score + "\n");
                }
            }

            // llegar a la meta → subir ronda, subir velocidad, sumar bonus y respawn
            if (p.x == s.goalX && p.y == s.goalY) {
                p.score += 100; // bonus de ronda
                p.round += 1;
                s.enemySpeedSteps += 1; // más rápido cada ronda
                p.x = s.spawnX; p.y = s.spawnY;
                sendToPlayerAndSpectators(pid, "SCORE " + pid + " " + p.score + "\n");
                sendToPlayerAndSpectators(pid, "ROUND " + pid + " " + p.round + " " + s.enemySpeedSteps + "\n");
            }
        }

        // 3) STATE (pos, ack, score, round) a jugadores; OBS a espectadores
        int t = tickSeq.incrementAndGet();

        // STATE a jugadores
        StringBuilder state = new StringBuilder();
        state.append("STATE ").append(t).append(' ');
        for (Map.Entry<Integer, Player> e : players.entrySet()) {
            Player p = e.getValue();
            state.append(p.id).append(' ').append(p.x).append(' ').append(p.y).append(' ')
                 .append(p.lastAckSeq).append(' ').append(p.score).append(' ').append(p.round).append(';');
        }
        state.append('\n');
        for (ClientHandler ch : playerClients) ch.sendLine(state.toString());

        // OBS filtrado por jugador
        players.forEach((pid, p) -> {
            var ls = spectatorsByPlayer.get(pid);
            if (ls != null && !ls.isEmpty()) {
                String obs = "OBS " + pid + " " + t + " "
                           + p.id + " " + p.x + " " + p.y + " "
                           + p.lastAckSeq + " " + p.score + " " + p.round + ";\n";
                for (ClientHandler ch : ls) ch.sendLine(obs);
            }
        });
    }

    /* ========= API para ClientHandler ========= */
    /**
     * Maneja la solicitud de un cliente para unirse como jugador.
     * <p>Asigna un nuevo ID, crea el objeto {@link Player} y una {@link GameSession},
     * y notifica al cliente con el ID asignado.</p>
     * @param c El manejador del cliente que solicita unirse.
     * @param name El nombre de usuario del jugador.
     */
    void onJoin(ClientHandler c, String name) {
        if (players.size() >= 2) { c.sendLine("ERR MAX_PLAYERS\n"); return; }

        int id = nextId.getAndIncrement();
        Player p = new Player(id, name);
        players.put(id, p);
        byClient.put(c, id);
        playerClients.add(c);

        // crear sesión del jugador con clones de la plantilla admin
        GameSession s = new GameSession(id);
        s.loadFromTemplates(templateEnemies, templateFruits);
        sessions.put(id, s);

        c.sendLine("ASSIGN " + id + "\n");
        // si había espectadores en espera: activarlos
        var waiting = waitingSpectatorsByPlayer.remove(id);
        if (waiting != null && !waiting.isEmpty()) {
            var list = spectatorsByPlayer.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
            for (ClientHandler w : waiting) { list.add(w); w.sendLine("OK SPECTATING " + id + "\n"); }
        }
        System.out.println("[JAVA] JOIN -> id=" + id + " name=" + name);
    }
    /**
     * Maneja el input de movimiento de un jugador.
     *
     * <p>Crea un nuevo {@link InputEvent} y lo encola en la {@link #inputQueue}
     * para su procesamiento en el siguiente {@link #tick()}.</p>
     *
     * @param c El manejador del cliente que envía el input.
     * @param seq El número de secuencia del input.
     * @param dx El desplazamiento en X.
     * @param dy El desplazamiento en Y.
     */
    void onInput(ClientHandler c, int seq, int dx, int dy) {
        Integer id = byClient.get(c);
        if (id == null) { c.sendLine("ERR NOT_JOINED\n"); return; }
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) { c.sendLine("ERR STEP_TOO_BIG\n"); return; }
        inputQueue.offer(new InputEvent(id, seq, dx, dy));
    }
    /**
     * Maneja la solicitud de un cliente para unirse como espectador.
     *
     * <p>Si el jugador existe, lo añade a la lista de espectadores. Si no,
     * lo añade a la lista de espera ({@link #waitingSpectatorsByPlayer}).</p>
     *
     * @param c El manejador del cliente que solicita ser espectador.
     * @param playerId El ID del jugador al que desea observar.
     */
    void onSpectate(ClientHandler c, int playerId) {
        if (players.containsKey(playerId)) {
            var list = spectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
            if (list.size() >= 2) { c.sendLine("ERR MAX_SPECTATORS\n"); return; }
            list.add(c);
            c.sendLine("OK SPECTATING " + playerId + "\n");
            return;
        }
        var waitList = waitingSpectatorsByPlayer.computeIfAbsent(playerId, k -> new CopyOnWriteArrayList<>());
        waitList.add(c);
        c.sendLine("OK WAITING " + playerId + "\n");
    }
    /**
     * Método llamado por el {@link ClientHandler} cuando su hilo termina (por error de I/O o desconexión).
     *
     * @param c El manejador del cliente a remover.
     */
    void onQuit(ClientHandler c) {
        Integer id = byClient.remove(c);
        if (id != null) {
            endPlayerSession(id);
        } else {
            spectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
            waitingSpectatorsByPlayer.forEach((pid, ls) -> ls.remove(c));
        }
    }

    /* ========= Utilidades ========= */
    /**
     * Finaliza la sesión de juego de un jugador específico.
     * <p>Elimina el {@link Player} y la {@link GameSession}, notifica a sus
     * espectadores, y fuerza el cierre de la conexión del {@link ClientHandler}
     * asociado si aún está conectado.</p>
     *
     * @param playerId El ID del jugador cuya sesión debe terminar.
     */
    private void endPlayerSession(int playerId) {
        // avisar a espectadores y limpiar
        var ls = spectatorsByPlayer.remove(playerId);
        if (ls != null) for (ClientHandler sp : ls) sp.sendLine("OBS_END " + playerId + "\n");
        waitingSpectatorsByPlayer.remove(playerId);

        // cerrar handler del jugador si sigue conectado
        ClientHandler toClose = null;
        for (Map.Entry<ClientHandler,Integer> e : byClient.entrySet()) {
            if (e.getValue() == playerId) { toClose = e.getKey(); break; }
        }
        if (toClose != null) {
            byClient.remove(toClose);
            playerClients.remove(toClose);
            toClose.sendLine("BYE\n");
            toClose.close();
            clients.remove(toClose);
        }
        players.remove(playerId);
        sessions.remove(playerId);
        System.out.println("[JAVA] Fin de sesión -> id=" + playerId);
    }

    private void sendToPlayerAndSpectators(int playerId, String line) {
        // a jugador:
        for (Map.Entry<ClientHandler,Integer> e : byClient.entrySet()) {
            if (e.getValue() == playerId) e.getKey().sendLine(line);
        }
        // a sus espectadores:
        var ls = spectatorsByPlayer.get(playerId);
        if (ls != null) for (ClientHandler ch : ls) ch.sendLine(line);
    }

    public void broadcast(String line) {
        for (ClientHandler ch : clients) ch.sendLine(line);
    }

    void removeClient(ClientHandler c) {
        clients.remove(c);
        onQuit(c);
        System.out.println("[JAVA] Cliente removido. Conectados: " + clients.size());
    }

    public void stop() {
        try {
            ticker.shutdownNow();
            for (ClientHandler c : clients) c.close();
            clients.clear();
            pool.shutdownNow();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            System.out.println("[JAVA] Servidor detenido.");
        } catch (IOException e) { e.printStackTrace(); }
    }

    /* ========= Consola Admin (Abstract Factory) ========= */
    /**
     * Bucle de ejecución para la consola de administración.
     * <p>Lee comandos desde la entrada estándar (System.in) y los procesa
     * en {@link #handleAdminCommand(String)}.</p>
     */
    private void adminLoop() {
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) { stop(); break; }
                handleAdminCommand(line);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Procesa comandos de administración ingresados por consola.
     *
     * <p>Permite al administrador crear plantillas de enemigos y frutas
     * que serán clonadas en las nuevas {@link GameSession} (patrón Abstract Factory).</p>
     * <ul>
     * <li>Sintaxis ENEMIGO: <code>ADMIN CROCODILE &lt;RED|BLUE&gt; &lt;liana&gt; [y]</code></li>
     * <li>Sintaxis FRUTA CREAR: <code>ADMIN FRUIT CREATE &lt;liana&gt; &lt;y&gt; &lt;points&gt;</code></li>
     * <li>Sintaxis FRUTA BORRAR: <code>ADMIN FRUIT DELETE &lt;liana&gt; &lt;y&gt;</code></li>
     * </ul>
     * @param line El comando de administración completo.
     */
    private void handleAdminCommand(String line) {
        try {
            String[] t = line.split("\\s+");
            if (t.length >= 4 && "ADMIN".equalsIgnoreCase(t[0]) && "CROCODILE".equalsIgnoreCase(t[1])) {
                String type = t[2]; int liana = Integer.parseInt(t[3]);
                int y = (t.length > 4) ? Integer.parseInt(t[4]) : MIN_Y;
                templateEnemies.add(factory.createCrocodile(type, liana, y));
                System.out.println("[ADMIN] CROCODILE " + type + " @" + liana + "," + y + " (plantilla)");
            } else if (t.length >= 6 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "CREATE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]), pts = Integer.parseInt(t[5]);
                templateFruits.add(factory.createFruit(l, y, pts));
                System.out.println("[ADMIN] FRUIT +" + l + "," + y + " pts=" + pts + " (plantilla)");
            } else if (t.length >= 5 && "ADMIN".equalsIgnoreCase(t[0]) && "FRUIT".equalsIgnoreCase(t[1]) && "DELETE".equalsIgnoreCase(t[2])) {
                int l = Integer.parseInt(t[3]), y = Integer.parseInt(t[4]);
                templateFruits.removeIf(f -> f.getX()==l && f.getY()==y);
                System.out.println("[ADMIN] FRUIT -" + l + "," + y + " (plantilla)");
            } else {
                System.out.println("[ADMIN] Comando inválido");
            }
        } catch (Exception e) {
            System.out.println("[ADMIN] Error: " + e.getMessage());
        }
    }
    //Main
    /**
     * Punto de entrada principal para el servidor.
     *
     * <p>Configura un <b>Shutdown Hook</b> para asegurar el cierre correcto
     * del servidor (llamando a {@link #stop()}) cuando la JVM se detiene
     * (por ejemplo, con CTRL+C) e inicia el servidor.</p>
     *
     * @param args Argumentos de la línea de comandos (no utilizados).
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[JAVA] Shutdown hook → cerrando servidor...");
            Server.getInstance().stop();
        }));
        try { Server.getInstance().start(); } catch (IOException e) { e.printStackTrace(); }
    }
}

