/* Tilecast policy: upstream diagnostics can contain authenticated stream URLs. */
#include "log.h"

static void discard_rtmp_log(int level, const char *format, va_list args)
{
    (void)level;
    (void)format;
    (void)args;
}

__attribute__((constructor)) static void configure_rtmp_logging(void)
{
    RTMP_LogSetCallback(discard_rtmp_log);
    RTMP_LogSetLevel(RTMP_LOGCRIT);
}
