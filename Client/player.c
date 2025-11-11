// Ruta: Client/player.c
#define _WINSOCK_DEPRECATED_NO_WARNINGS
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include "constants.h"
#pragma comment(lib, "Ws2_32.lib")

volatile int running = 1, myId = 0, lastAck = 0;

int recv_line(SOCKET s, char *out, int maxlen){
    int total=0; while(total < maxlen-1){
        char c; int n=recv(s,&c,1,0);
        if(n==0) return 0; if(n<0) return -1;
        if(c=='\n') break; if(c!='\r') out[total++]=c;
    } out[total]='\0'; return total;
}
DWORD WINAPI reader_thread(LPVOID p){
    SOCKET s=*(SOCKET*)p; char line[LINE_MAX];
    while(running){
        int n=recv_line(s,line,sizeof(line));
        if(n<=0){ printf("[C] Conexión cerrada.\n"); running=0; break; }
        printf("[C]<- %s\n", line);
        if(!strncmp(line,"ASSIGN ",7)) myId=atoi(line+7);
        else if(!strncmp(line,"STATE ",6)){
            char *seg=strchr(line,' '); if(!seg) continue;
            seg++; seg=strchr(seg,' '); if(!seg) continue; seg++;
            char *p2=seg;
            while(p2 && *p2){
                int id,x,y,ack,score,round,consumed=0;
                if(sscanf(p2,"%d %d %d %d %d %d;%n",&id,&x,&y,&ack,&score,&round,&consumed)==6){
                    if(id==myId) lastAck=ack;
                    p2+=consumed;
                } else break;
            }
        } else if(!strncmp(line,"BYE",3)) running=0;
    } return 0;
}
int send_line(SOCKET s,const char* txt){ return send(s,txt,(int)strlen(txt),0); }

int main(void){
    WSADATA wsa; WSAStartup(MAKEWORD(2,2), &wsa);
    SOCKET sock=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    struct sockaddr_in srv={0};
    srv.sin_family=AF_INET; srv.sin_port=htons(HOST_PORT);
    srv.sin_addr.s_addr=inet_addr(HOST_IP);

    printf("[C] Conectando a %s:%d ...\n", HOST_IP, HOST_PORT);
    if(connect(sock,(struct sockaddr*)&srv,sizeof(srv))!=0){
        printf("[C] connect() fallo (%d)\n", WSAGetLastError()); return 1;
    }
    printf("[C] Conectado.\n");

    HANDLE h=CreateThread(NULL,0,reader_thread,&sock,0,NULL);

    send_line(sock,"JOIN Josepa\n");
    Sleep(JOIN_PAUSE_MS);

    int seq=1; char buf[SMALL_BUF];
    sprintf(buf,"INPUT %d %d %d\n",seq++, 1, 0); send_line(sock,buf); Sleep(BETWEEN_INPUT_MS);
    sprintf(buf,"INPUT %d %d %d\n",seq++, 0, 1); send_line(sock,buf); Sleep(BETWEEN_INPUT_MS);
    sprintf(buf,"INPUT %d %d %d\n",seq++, -1, 0); send_line(sock,buf); Sleep(BETWEEN_INPUT_MS);

    send_line(sock,"PING\n");

    // Mantén viva la sesión para que el espectador pueda observar
    Sleep(BEFORE_QUIT_MS);

    send_line(sock,"QUIT\n");

    while(running) Sleep(50);
    WaitForSingleObject(h,INFINITE);
    closesocket(sock); WSACleanup();
    printf("[C] Conexión cerrada correctamente.\n");
    return 0;
}



