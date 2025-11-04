/*
 * shared-mem-with-posix-semaphore.c - 
 * using a POSIX semaphore to synchronize access
 * to a shared memory segment.
 * Compiling: gcc -o shared-mem-posix shared-mem-with-posix-semaphore.c -lpthread
 */
#include <stdio.h>       /* standard I/O routines.               */
#include <sys/types.h>   /* various type definitions.            */
#include <sys/ipc.h>     /* general SysV IPC structures          */
#include <sys/shm.h>     /* shared memory functions and structs. */
#include <semaphore.h>   /* POSIX semaphore functions.           */
#include <unistd.h>      /* fork(), etc.                         */
#include <sys/wait.h>    /* wait(), etc.                         */
#include <time.h>        /* nanosleep(), etc.                    */
#include <stdlib.h>      /* rand(), etc.                         */
#include <string.h>      /* strcpy(), etc.                       */
#include <fcntl.h>       /* O_* constants                        */

#define SEM_NAME "/country_sem"  /* Name for the POSIX semaphore.    */

/* define a structure to be used in the given shared memory segment. */
struct country {
    char name[30];
    char capital_city[30];
    int population;
};

/*
 * function: random_delay. delay the executing process 
 *           for a random number of nano-seconds.
 * input:    none.
 * output:   none.
 */
void
random_delay()
{
    static int initialized = 0;
    int random_num;
    struct timespec delay;     /* used for wasting time. */
    
    if (!initialized) {
        srand(time(NULL));
        initialized = 1;
    }
    random_num = rand() % 300 + 100; /* 100 to 400 milliseconds */
    delay.tv_sec = 0;
    delay.tv_nsec = 1000000 * random_num; /* milliseconds */
    nanosleep(&delay, NULL);
}

/*
 * function: sem_lock. locks the POSIX semaphore, for exclusive access to a resource.
 * input:    pointer to semaphore.
 * output:   none.
 */
void
sem_lock(sem_t* sem)
{
    if (sem_wait(sem) == -1) {
        perror("sem_wait");
        exit(1);
    }
}

/*
 * function: sem_unlock. unlocks the POSIX semaphore.
 * input:    pointer to semaphore.
 * output:   none.
 */
void
sem_unlock(sem_t* sem)
{
    if (sem_post(sem) == -1) {
        perror("sem_post");
        exit(1);
    }
}

/*
 * function: add_country. adds a new country to the countries array in the
 *           shared memory segment. Handles locking using a POSIX semaphore.
 * input:    pointer to semaphore, pointer to countries counter, pointer to
 *           countries array, data to fill into country.
 * output:   none.
 */
void
add_country(sem_t* sem, int* countries_num, struct country* countries,
            char* country_name, char* capital_city, int population)
{
    sem_lock(sem);
    strcpy(countries[*countries_num].name, country_name);
    strcpy(countries[*countries_num].capital_city, capital_city);
    countries[*countries_num].population = population;
    (*countries_num)++;

    for (int i = 0; i < 5; i++)
        random_delay();

    sem_unlock(sem);
}

/*
 * function: do_child. runs the child process's code, for populating
 *           the shared memory segment with data.
 * input:    pointer to semaphore, pointer to countries counter, pointer to
 *           countries array.
 * output:   none.
 */
void
do_child(sem_t* sem, int* countries_num, struct country* countries)
{
    /* Pre-defined array of countries to add */
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
    
    int num_countries = sizeof(country_data) / sizeof(country_data[0]);
    int i;
    
    /* Loop through the pre-defined array and add each country */
    for (i = 0; i < num_countries; i++) {
        add_country(sem, countries_num, countries,
                    country_data[i].name, 
                    country_data[i].capital_city, 
                    country_data[i].population);
        random_delay();
    }
}

/*
 * function: do_parent. runs the parent process's code, for reading and
 *           printing the contents of the 'countries' array in the shared
 *           memory segment.
 * input:    pointer to semaphore, pointer to countries counter, pointer to
 *           countries array.
 * output:   printout of countries array contents.
 */
void
do_parent(sem_t* sem, int* countries_num, struct country* countries)
{
    int i, num_loops;
    
    for (num_loops = 0; num_loops < 12; num_loops++) {
        /* now, print out the countries data. */
        sem_lock(sem);
        printf("---------------------------------------------------\n");
        printf("Number Of Countries: %d\n", *countries_num);
        for (i = 0; i < (*countries_num); i++) {
           printf("Country %2d\t%s\t%s\t%d\n", 
                  i+1, countries[i].name, countries[i].capital_city, countries[i].population);
        }
        printf("---------------------------------------------------\n");
        sem_unlock(sem);
        random_delay();
    }
}

int main(int argc, char* argv[])
{
    sem_t* sem;                    /* pointer to the POSIX semaphore.    */
    int shm_id;                    /* ID of the shared memory segment.   */
    char* shm_addr;                /* address of shared memory segment.  */
    int* countries_num;            /* number of countries in shared mem. */
    struct country* countries;     /* countries array in shared mem.     */
    struct shmid_ds shm_desc;
    pid_t pid;                     /* PID of child process.              */
    
    /* create/open a named POSIX semaphore, initialized to 1 (binary semaphore) */
    sem = sem_open(SEM_NAME, O_CREAT | O_EXCL, 0600, 1);
    if (sem == SEM_FAILED) {
        perror("main: sem_open");
        exit(1);
    }
    
    /* allocate a shared memory segment with size of 4096 bytes. */
    shm_id = shmget(100, 4096, IPC_CREAT | IPC_EXCL | 0600);
    if (shm_id == -1) {
        perror("main: shmget");
        /* cleanup semaphore before exit */
        sem_close(sem);
        sem_unlink(SEM_NAME);
        exit(1);
    }
    
    /* attach the shared memory segment to our process's address space. */
    shm_addr = shmat(shm_id, NULL, 0);
    if (shm_addr == (char*)-1) { /* operation failed. */
        perror("main: shmat");
        /* cleanup resources before exit */
        shmctl(shm_id, IPC_RMID, &shm_desc);
        sem_close(sem);
        sem_unlink(SEM_NAME);
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
            /* cleanup resources before exit */
            shmdt(shm_addr);
            shmctl(shm_id, IPC_RMID, &shm_desc);
            sem_close(sem);
            sem_unlink(SEM_NAME);
            exit(1);
            break;
        case 0:
            /* child process */
            do_child(sem, countries_num, countries);
            /* child cleanup */
            shmdt(shm_addr);
            sem_close(sem);
            exit(0);
            break;
        default:
            /* parent process */
            do_parent(sem, countries_num, countries);
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
    
    /* close and unlink the POSIX semaphore */
    if (sem_close(sem) == -1) {
        perror("main: sem_close");
    }
    if (sem_unlink(SEM_NAME) == -1) {
        perror("main: sem_unlink");
    }
    
    return 0;
}