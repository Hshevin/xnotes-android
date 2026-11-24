#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *xn_pdf_open(const void *bytes, int length, const char *password);
void *xn_pdf_open_file(const char *path, const char *password);
int xn_pdf_count(void *handle);
int xn_pdf_size(void *handle, int index, double *width, double *height);
int xn_pdf_render(void *handle, int index, int width, int height, int32_t *pixels);
void xn_pdf_close(void *handle);

int xn_ts_count(void);
const char *xn_ts_name(int index);
char *xn_ts_parse(int index, const void *bytes, int length);
void xn_ts_free(char *expression);

#ifdef __cplusplus
}
#endif
