/*
 * shared-mem-with-sysv-semaphore.c -
 * using a System V semaphore to synchronize access
 * to a shared memory segment.
 * Compiling: gcc -o shared-mem-sysv shared-mem-with-sysv-semaphore.c
 */
#include <stdio.h>       /* standard I/O routines.               */
#include <sys/types.h>   /* various type definitions.            */
#include <sys/ipc.h>     /* general SysV IPC structures          */
#include <sys/shm.h>     /* shared memory functions and structs. */
#include <sys/sem.h>     /* System V semaphore functions.        */
#include <unistd.h>      /* fork(), etc.                         */
#include <sys/wait.h>    /* wait(), etc.                         */
#include <time.h>        /* nanosleep(), etc.                    */
#include <stdlib.h>      /* rand(), etc.                         */
#include <string.h>      /* strcpy(), etc.                       */

/* Keys for IPC objects (choose any nonzero). */
#define SHM_KEY 100
#define SEM_KEY 200

/* Some systems (e.g., glibc) require defining union semun ourselves. */
#ifndef __SEM_SEMUN_DEFINED
union semun {
    int              val;    /* value for SETVAL */
    struct semid_ds *buf;    /* buffer for IPC_STAT, IPC_SET */
    unsigned short  *array;  /* array for GETALL, SETALL */
    struct seminfo  *__buf;  /* buffer for IPC_INFO (Linux-specific) */
};
#endif

/* define a structure to be used in the given shared memory segment. */
struct country {
    char name[30];
    char capital_city[30];
    int population;
};

/* --- timing helper --- */
static void random_delay(void)
{
    static int initialized = 0;
    int random_num;
    struct timespec delay;

    if (!initialized) {
        srand((unsigned int)time(NULL) ^ (unsigned int)getpid());
        initialized = 1;
    }
    random_num = rand() % 300 + 100; /* 100 to 400 milliseconds */
    delay.tv_sec = 0;
    delay.tv_nsec = 1000000L * random_num;
    nanosleep(&delay, NULL);
}

/* --- System V semaphore helpers --- */

static int sem_create_and_init(key_t key, int initial_value)
{
    int semid;
    union semun arg;

    /* Create a set with 1 semaphore, exclusive so we know we created it. */
    semid = semget(key, 1, IPC_CREAT | IPC_EXCL | 0600);
    if (semid == -1) {
        perror("semget");
        return -1;
    }

    /* Initialize semaphore #0 to initial_value. */
    arg.val = initial_value;
    if (semctl(semid, 0, SETVAL, arg) == -1) {
        perror("semctl(SETVAL)");
        /* Cleanup if init failed */
        semctl(semid, 0, IPC_RMID);
        return -1;
    }

    return semid;
}

static void sem_lock(int semid)
{
    struct sembuf op = {0, -1, SEM_UNDO}; /* P operation */
    if (semop(semid, &op, 1) == -1) {
        perror("semop(-1)");
        exit(1);
    }
}

static void sem_unlock(int semid)
{
    struct sembuf op = {0, +1, SEM_UNDO}; /* V operation */
    if (semop(semid, &op, 1) == -1) {
        perror("semop(+1)");
        exit(1);
    }
}

/* --- app logic, now parameterized by System V semid --- */

static void add_country(int semid, int* countries_num, struct country* countries,
                        const char* country_name, const char* capital_city, int population)
{
    sem_lock(semid);

    strcpy(countries[*countries_num].name, country_name);
    strcpy(countries[*countries_num].capital_city, capital_city);
    countries[*countries_num].population = population;
    (*countries_num)++;

    for (int i = 0; i < 5; i++)
        random_delay();

    sem_unlock(semid);
}

static void do_child(int semid, int* countries_num, struct country* countries)
{
    struct country country_data[] = {
        {"U.S.A", "Washington", 331},
        {"China", "Beijing", 1411},
        {"Russia", "Moscow", 144},
        {"India", "New Delhi", 1380},
        {"Japan", "Tokyo", 125},
        {"Brazil", "Brasilia", 213},
        {"Nigeria", "Abuja", 218},
        {"Germany", "Berlin", 83},
        {"Turkey", "Ankara", 84},
        {"Iran", "Tehran", 85},
        {"Vietnam", "Hanoi", 98},
        {"Egypt", "Cairo", 104}
    };

    int num_countries = (int)(sizeof(country_data) / sizeof(country_data[0]));

    for (int i = 0; i < num_countries; i++) {
        add_country(semid, countries_num, countries,
                    country_data[i].name,
                    country_data[i].capital_city,
                    country_data[i].population);
        random_delay();
    }
}

static void do_parent(int semid, int* countries_num, struct country* countries)
{
    for (int loops = 0; loops < 12; loops++) {
        sem_lock(semid);
        printf("---------------------------------------------------\n");
        printf("Number Of Countries: %d\n", *countries_num);
        for (int i = 0; i < (*countries_num); i++) {
            printf("Country %2d\t%s\t%s\t%d\n",
                   i + 1, countries[i].name, countries[i].capital_city, countries[i].population);
        }
        printf("---------------------------------------------------\n");
        sem_unlock(semid);
        random_delay();
    }
}

int main(void)
{
    int semid;                     /* System V semaphore id.             */
    int shm_id;                    /* ID of the shared memory segment.   */
    char* shm_addr;                /* address of shared memory segment.  */
    int* countries_num;            /* number of countries in shared mem. */
    struct country* countries;     /* countries array in shared mem.     */
    struct shmid_ds shm_desc;
    pid_t pid;

    /* Create & init a SysV semaphore as a binary semaphore (1). */
    semid = sem_create_and_init(SEM_KEY, 1);
    if (semid == -1) {
        /* error already printed */
        exit(1);
    }

    /* allocate a shared memory segment with size of 4096 bytes. */
    shm_id = shmget(SHM_KEY, 4096, IPC_CREAT | IPC_EXCL | 0600);
    if (shm_id == -1) {
        perror("main: shmget");
        /* cleanup semaphore before exit */
        semctl(semid, 0, IPC_RMID);
        exit(1);
    }

    /* attach the shared memory segment to our process's address space. */
    shm_addr = (char*)shmat(shm_id, NULL, 0);
    if (shm_addr == (char*)-1) {
        perror("main: shmat");
        shmctl(shm_id, IPC_RMID, &shm_desc);
        semctl(semid, 0, IPC_RMID);
        exit(1);
    }

    /* create a countries index on the shared memory segment. */
    countries_num = (int*) shm_addr;
    *countries_num = 0;
    countries = (struct country*) ((void*)shm_addr + sizeof(int));

    /* fork-off a child process that'll populate the memory segment. */
    pid = fork();
    switch (pid) {
        case -1:
            perror("fork");
            shmdt(shm_addr);
            shmctl(shm_id, IPC_RMID, &shm_desc);
            semctl(semid, 0, IPC_RMID);
            exit(1);
            break;
        case 0: /* child */
            do_child(semid, countries_num, countries);
            shmdt(shm_addr);
            /* child does NOT remove the semaphore */
            _exit(0);
            break;
        default: /* parent */
            do_parent(semid, countries_num, countries);
            break;
    }

    /* wait for child process's termination. */
    {
        int child_status;
        wait(&child_status);
    }

    /* detach the shared memory segment from our process's address space. */
    if (shmdt(shm_addr) == -1) {
        perror("main: shmdt");
    }

    /* deallocate the shared memory segment. */
    if (shmctl(shm_id, IPC_RMID, &shm_desc) == -1) {
        perror("main: shmctl");
    }

    /* remove the System V semaphore set */
    if (semctl(semid, 0, IPC_RMID) == -1) {
        perror("semctl(IPC_RMID)");
    }

    return 0;
}
