// Ruta: Server/GameSession.java
package Server;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import Server.entities.Enemy;
import Server.entities.Fruit;
import Server.entities.RedCroc;
import Server.entities.BlueCroc;
import Server.entities.SimpleFruit;

public class GameSession {
    public final int playerId;
    public int spawnX, spawnY;
    public int goalX, goalY;
    public final CopyOnWriteArrayList<Enemy> enemies = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<Fruit> fruits  = new CopyOnWriteArrayList<>();

    // “Velocidad lógica” de enemigos para este jugador (pasos por tick)
    public int enemySpeedSteps = 1;

    public GameSession(int playerId) { 
        this.playerId = playerId;
        this.spawnX = 0;  // S está en x=0, y=1
        this.spawnY = 1;
        this.goalX  = 2;  // “GGG” empieza en x=2, y=10
        this.goalY  = 10; 
    }

    /** Clona listas plantilla del servidor a esta sesión */
    public void loadFromTemplates(List<Enemy> tplEnemies, List<Fruit> tplFruits) {
        enemies.clear();
        fruits.clear();

        for (Enemy e : tplEnemies) {
            String type = e.getType();
            // usar nombres simples gracias a los imports
            enemies.add("RED".equalsIgnoreCase(type)
                    ? new RedCroc(e.getX(), e.getY())
                    : new BlueCroc(e.getX(), e.getY()));
        }
        for (Fruit f : tplFruits) {
            fruits.add(new SimpleFruit(f.getX(), f.getY(), f.getPoints()));
        }
    }
}


