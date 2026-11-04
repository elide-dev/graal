/* Runtime and archive-link probe for check-sanitizer-natives.sh. */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>

extern void sys_select_FD_ZERO(fd_set *set);
extern void sys_select_FD_SET(int fd, fd_set *set);
extern unsigned ZSTD_isError(size_t code);
extern size_t ZSTD_decompress(void *dst, size_t capacity, const void *src, size_t size);

#if defined(PROBE_TSAN)
static fd_set shared;
static void *race(void *unused) {
    (void) unused;
    for (int i = 0; i < 10000; i++) {
        sys_select_FD_SET(1, &shared);
    }
    return NULL;
}
#endif

int main(int argc, char **argv) {
    (void) argv;
    if (argc > 1) {
#if defined(PROBE_ASAN)
        fd_set *small = malloc(sizeof(fd_set) - sizeof(long));
        sys_select_FD_SET(FD_SETSIZE - 1, small);
        free(small);
#elif defined(PROBE_TSAN)
        pthread_t a, b;
        pthread_create(&a, NULL, race, NULL);
        pthread_create(&b, NULL, race, NULL);
        pthread_join(a, NULL);
        pthread_join(b, NULL);
#elif defined(PROBE_MSAN)
        int *uninitialized = malloc(sizeof(int));
        fd_set set;
        memset(&set, 0, sizeof(set));
        sys_select_FD_ZERO(&set);
        sys_select_FD_SET(*uninitialized, &set);
        free(uninitialized);
#endif
        return 0;
    }
    fd_set set;
    /* glibc can implement FD_ZERO with inline assembly, whose stores MSan cannot observe. */
    memset(&set, 0, sizeof(set));
    sys_select_FD_ZERO(&set);
    sys_select_FD_SET(1, &set);
    char output[16];
    return !FD_ISSET(1, &set) || !ZSTD_isError(ZSTD_decompress(output, sizeof(output), "invalid", 7));
}
