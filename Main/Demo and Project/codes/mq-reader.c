#include <stdio.h>
#include <sys/ipc.h>
#include <sys/msg.h>

// structure for message queue
struct mesg_buffer {
    long mesg_type;
    char mesg_text[1000];
} message;

int main()
{
    key_t key;
    int msgid;

    // ftok to generate unique key
    key = ftok("progfile", 65);

    // msgget creates a message queue
    // and returns identifier
    msgid = msgget(key, 0666 | IPC_CREAT);

    // waiting for message
    printf("Waiting for message..."); fflush(stdout);

    // msgrcv to receive message
    msgrcv(msgid, &message, sizeof(message), 123, 0);

    // display the message
    printf("\nData Received is : %s \nMessage Type is %ld\n", 
        message.mesg_text, message.mesg_type);

    // to destroy the message queue
    msgctl(msgid, IPC_RMID, NULL);

    return 0;
}