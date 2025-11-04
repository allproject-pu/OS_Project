#include <stdio.h>
#include <sys/ipc.h>
#include <sys/msg.h>

#define MAX 900

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
    message.mesg_type = 123;

    printf("Enter message to write : ");
    fgets(message.mesg_text,MAX,stdin);

    // msgsnd to send message
    msgsnd(msgid, &message, sizeof(message), 0);

    // display the message
    printf("Data send is : %s \n", message.mesg_text);

    return 0;
}