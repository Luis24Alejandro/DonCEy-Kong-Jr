// Ruta: Client/player.c - Cliente Jugador

#include "raylib.h" 

#define NOGDI
#define NOUSER
#define WIN32_LEAN_AND_MEAN 
#define NOMINMAX
#define WINSOCK_DEPRECATED_NO_WARNINGS

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <process.h> 
#pragma comment(lib, "Ws2_32.lib")

volatile int running = 1, myId = 0; 
// Prototipos
int recv_line(SOCKET s, char *out, int maxlen);
int send_line(SOCKET s, const char* txt);
DWORD WINAPI reader_thread(LPVOID p);


// Funciones auxiliares

int recv_line(SOCKET s, char *out, int maxlen){
    int total=0; while(total < maxlen-1){
        char c; int n = recv(s, &c, 1, 0);
        if(n==0) return 0; if(n<0) return -1;
        if(c=='\n') break; if(c!='\r') out[total++] = c;
    } out[total]='\0'; return total;
}

int send_line(SOCKET s, const char* txt){ 
    return send(s, txt, (int)strlen(txt), 0); 
}

// Hilo Lector (Etiqueta [C] - Mantiene la ventana abierta)
DWORD WINAPI reader_thread(LPVOID p){
    SOCKET sock=*(SOCKET*)p; char line[4096];
    
    while(running){ 
        int n = recv_line(sock, line, sizeof(line));
        
        if(n<=0){ 
            printf("[C] Conexión perdida, cerrando hilo lector. (La ventana permanece abierta)\n"); 
            break; // Cierra solo el hilo de socket
        }

        printf("[C]<- %s\n", line); 
        
        if(!strncmp(line,"ASSIGN ",7)) myId=atoi(line+7);
        if(strcmp(line,"BYE")==0) running=0; // Cierra la ventana solo con BYE
    } 
    return 0;
}

// FUNCIÓN PRINCIPAL
int main(void){ 
 
    // Inicialización de Sockets
    WSADATA wsa; WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in server={0};
    
    // Usamos valores fijos 127.0.0.1 y 5000
    server.sin_family=AF_INET; server.sin_port=htons(5000); 
    server.sin_addr.s_addr=inet_addr("127.0.0.1"); 

    printf("[C] Conectando...\n");
    if(connect(sock,(struct sockaddr*)&server,sizeof(server))!=0){ 
        printf("[C] connect() fallo (%d)\n", WSAGetLastError()); // return 1; <--- Comentado para abrir la ventana siempre
    }
    printf("[C] Conectado (o intento fallido).\n");

    HANDLE h=CreateThread(NULL,0,reader_thread,&sock,0,NULL);

    send_line(sock, "JOIN Josepa\n"); // Comando JOIN
    
    // Inicialización de Raylib
    const int screenWidth = 800; 
    const int screenHeight = 600;
    InitWindow(screenWidth, screenHeight, "DonCEy Kong Jr - Jugador");
    SetTargetFPS(60); 

    // Bucle Principal (Game Loop)
    while (!WindowShouldClose() && running) { 
        // Lógica de INPUT aquí...
        BeginDrawing();
            ClearBackground(BLACK);
            DrawText("CLIENTE JUGADOR ACTIVO", 20, 20, 20, RAYWHITE);
            DrawText("ESC para salir", 20, 50, 20, LIGHTGRAY);
        EndDrawing();
    }
    
    // Limpieza
    running = 0; 
    send_line(sock,"QUIT\n"); 
    
    WaitForSingleObject(h,INFINITE);
    closesocket(sock); 
    WSACleanup();
    CloseWindow();
    
    printf("[C] Cliente Jugador cerrado.\n"); 
    return 0;
}