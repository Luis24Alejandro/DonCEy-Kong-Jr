// Ruta: Client/player.c
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#pragma comment(lib, "Ws2_32.lib")

volatile int running = 1;
volatile int myId = 0;
volatile int lastAck = 0;

int recv_line(SOCKET s, char *out, int maxlen){
    int total=0;
    while(total < maxlen-1){
        char c; int n = recv(s, &c, 1, 0);
        if(n==0) return 0; if(n<0) return -1;
        if(c=='\n') break; if(c!='\r') out[total++] = c;
    }
    out[total]='\0'; return total;
}

DWORD WINAPI reader_thread(LPVOID p){
    SOCKET s = *(SOCKET*)p;
    char line[4096];
    while(running){
        int n = recv_line(s, line, sizeof(line));
        if(n <= 0){ printf("[C] Conexión cerrada por el servidor.\n"); running=0; break; }
        printf("[C]<- %s\n", line);

        if (strncmp(line, "ASSIGN ", 7) == 0) myId = atoi(line + 7);
        else if (strncmp(line, "STATE ", 6) == 0) {
            char *seg = strchr(line,' '); if(!seg) continue;
            seg++; seg = strchr(seg,' '); if(!seg) continue; seg++;
            char *p = seg;
            while(p && *p){
                int id,x,y,ack,consumed=0;
                if(sscanf(p, "%d %d %d %d;%n", &id,&x,&y,&ack,&consumed)==4){
                    if(id==myId) lastAck = ack;
                    p += consumed;
                } else break;
            }
        } else if (strcmp(line, "BYE")==0) { running=0; }
    }
    return 0;
}

int send_line(SOCKET s, const char* txt){ return send(s, txt, (int)strlen(txt), 0); }

int main(void){
    WSADATA wsa; WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    struct sockaddr_in server={0};
    server.sin_family=AF_INET; server.sin_port=htons(5000);
    server.sin_addr.s_addr=inet_addr("127.0.0.1");

    printf("[C] Conectando a 127.0.0.1:5000 ...\n");
    if(connect(sock,(struct sockaddr*)&server,sizeof(server))!=0){ printf("[C] connect() fallo (%d)\n", WSAGetLastError()); return 1; }
    printf("[C] Conectado.\n");

    HANDLE h = CreateThread(NULL,0,reader_thread,&sock,0,NULL);

    send_line(sock, "JOIN Josepa\n");
    Sleep(200);

    int seq=1; char buf[128];
    sprintf(buf,"INPUT %d %d %d\n",seq++, 1, 0); send_line(sock, buf); Sleep(150);
    sprintf(buf,"INPUT %d %d %d\n",seq++, 0, 1); send_line(sock, buf); Sleep(150);
    sprintf(buf,"INPUT %d %d %d\n",seq++, -1, 0); send_line(sock, buf); Sleep(150);

    send_line(sock, "PING\n");

    // >>> Mantener la sesión abierta 5 segundos para que se pueda conectar el espectador
    Sleep(5000);

    send_line(sock, "QUIT\n");

    while(running) Sleep(50);
    WaitForSingleObject(h, INFINITE);
    closesocket(sock); WSACleanup();
    printf("[C] Conexión cerrada correctamente.\n");
    return 0;
}


