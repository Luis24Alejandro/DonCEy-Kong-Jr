// Ruta: Client/spectator.c - Cliente Espectador

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define NOGDI
#define NOUSER
#define NOSOUND

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "constants.h"
#include "raylib.h"
#include "map.h"

#pragma comment(lib, "Ws2_32.lib")

// =======================
//  ESTADO DEL ESPECTADOR
// =======================
typedef struct {
    int id;         // id del jugador que estoy observando
    int x;
    int y;
    int score;
    int round;
    int observing;  // 1 si ya hay OBS (jugador activo)
    int ended;      // 1 si llegó OBS_END -> partida terminada
} SpectatedPlayer;

static SpectatedPlayer target = {0};
static volatile int running = 1;    // controla SOLO el bucle de ventana
static volatile int netAlive = 1;   // hilo de red: deja de leer cuando es 0

// =======================
//  UTILIDADES RED
// =======================
int recv_line(SOCKET s, char *out, int maxlen){
    int total = 0;
    while (total < maxlen - 1) {
        char c;
        int n = recv(s, &c, 1, 0);
        if (n == 0)  return 0;   // conexión cerrada
        if (n < 0)   return -1;  // error
        if (c == '\n') break;
        if (c != '\r') out[total++] = c;
    }
    out[total] = '\0';
    return total;
}

int send_line(SOCKET s, const char* txt){
    return send(s, txt, (int)strlen(txt), 0);
}

// =======================
//  HILO LECTOR
// =======================
DWORD WINAPI reader_thread(LPVOID p){
    SOCKET sock = *(SOCKET*)p;
    char line[LINE_MAX];

    while (netAlive) {
        int n = recv_line(sock, line, sizeof(line));
        if (n <= 0) {
            printf("[SPEC] Conexion cerrada por el servidor (recv <= 0)\n");
            break;  // dejamos de leer, pero NO cerramos la ventana
        }

        printf("[SPEC]<- %s\n", line);

        // OK SPECTATING id
        if (strncmp(line, "OK SPECTATING ", 14) == 0) {
            target.observing = 1;
            target.ended = 0;
        }
        // OK WAITING id
        else if (strncmp(line, "OK WAITING ", 11) == 0) {
            target.observing = 0;
            target.ended = 0;
        }
        // OBS pid tick id x y ack score round;
        else if (strncmp(line, "OBS ", 4) == 0) {
            int pid, tick, id, x, y, ack, score, round, consumed;
            if (sscanf(line + 4, "%d %d %d %d %d %d %d %d;%n",
                       &pid,&tick,&id,&x,&y,&ack,&score,&round,&consumed) == 8) {
                if (pid == target.id) {
                    target.x = x;
                    target.y = y;
                    target.score = score;
                    target.round = round;
                    target.observing = 1;
                }
            }
        }
        // OBS_END id: el jugador terminó su sesión
        else if (strncmp(line, "OBS_END ", 8) == 0) {
            int id;
            if (sscanf(line + 8, "%d", &id) == 1 && id == target.id) {
                printf("[SPEC] OBS_END para jugador %d\n", id);
                target.ended = 1;      // marcamos que ya terminó
                target.observing = 0;  // ya no hay updates
                // NO tocamos running -> la ventana sigue abierta
            }
        }
        // BYE (respuesta a nuestro QUIT al cerrar)
        else if (strncmp(line, "BYE", 3) == 0) {
            printf("[SPEC] Servidor envio BYE (ignorado para ventana)\n");
            break; // ya no leemos más, pero la ventana se cierra solo con ESC
        }
    }

    return 0;
}

// =======================
//  MAIN
// =======================
int main(int argc, char** argv){
    if (argc < 2) {
        printf("Uso: spectator <playerId>\n");
        return 1;
    }

    target.id = atoi(argv[1]);

    // --- init red ---
    WSADATA wsa;
    WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);

    struct sockaddr_in srv = {0};
    srv.sin_family = AF_INET;
    srv.sin_port   = htons(HOST_PORT);
    srv.sin_addr.s_addr = inet_addr(HOST_IP);

    printf("[SPEC] Conectando al servidor %s:%d...\n", HOST_IP, HOST_PORT);
    if (connect(sock, (struct sockaddr*)&srv, sizeof(srv)) != 0) {
        printf("[SPEC] Error conectando (%d)\n", WSAGetLastError());
        // igual seguimos para mostrar ventana (no habrá updates)
    } else {
        printf("[SPEC] Conectado.\n");
    }

    HANDLE h = CreateThread(NULL, 0, reader_thread, &sock, 0, NULL);

    // Enviar SPECTATE <id>
    char cmd[SMALL_BUF];
    sprintf(cmd, "SPECTATE %d\n", target.id);
    send_line(sock, cmd);

    // --- Raylib ---
    const int screenWidth  = 800;
    const int screenHeight = 600;
    InitWindow(screenWidth, screenHeight, "DonCEy-Kong Jr - Espectador");
    SetTargetFPS(60);

    const int cellSize  = 40;
    const int mapPixelW = MAP_W * cellSize;
    const int mapPixelH = MAP_H * cellSize;
    const int offsetX   = (screenWidth  - mapPixelW) / 2;
    const int offsetY   = (screenHeight - mapPixelH) / 2;

    while (!WindowShouldClose() && running) {
        BeginDrawing();
        ClearBackground(DARKGRAY);

        // Fondo del mapa
        DrawRectangle(offsetX-4, offsetY-4, mapPixelW+8, mapPixelH+8, BLACK);

        // Tiles iguales al cliente jugador
        for (int y = 0; y < MAP_H; y++) {
            for (int x = 0; x < MAP_W; x++) {
                char t = MapGetTile(x, y);
                int px = offsetX + x * cellSize;
                int py = offsetY + (MAP_H - 1 - y) * cellSize;

                Color c;
                if (t == '.') continue;
                else if (t == '=') c = BROWN;
                else if (t == '|') c = DARKGREEN;
                else if (t == 'G') c = RED;
                else if (t == 'S') c = BLUE;
                else c = DARKBROWN;

                DrawRectangle(px, py, cellSize, cellSize, c);
            }
        }

        DrawText(TextFormat("Observando jugador: %d", target.id), 20, 20, 20, RAYWHITE);
        DrawText(TextFormat("Score: %d", target.score), 20, 50, 20, RAYWHITE);
        DrawText(TextFormat("Round: %d", target.round), 20, 80, 20, RAYWHITE);

        if (target.ended) {
            DrawText("Partida terminada. Jugador desconectado.",
                     80, screenHeight - 60, 20, RED);
        } else if (!target.observing) {
            DrawText("Esperando que el jugador este en partida...",
                     80, screenHeight - 60, 20, RAYWHITE);
        } else {
            // Dibujar al jugador SOLO si está siendo observado activamente
            int px = offsetX + target.x * cellSize;
            int py = offsetY + (MAP_H - 1 - target.y) * cellSize;
            DrawRectangle(px+4, py+4, cellSize-8, cellSize-8, YELLOW);
        }

        EndDrawing();
    }

    // Limpieza
    running = 0;
    netAlive = 0;
    send_line(sock, "QUIT\n");
    WaitForSingleObject(h, INFINITE);
    closesocket(sock);
    WSACleanup();
    CloseWindow();

    printf("[SPEC] Cliente Espectador cerrado.\n");
    return 0;
}

