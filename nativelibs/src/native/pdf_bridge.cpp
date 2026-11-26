#include "xnotes_native.h"
#include "public/fpdfview.h"

#include <climits>
#include <cstdlib>
#include <cstring>
#include <mutex>

namespace {
struct Document {
    FPDF_DOCUMENT pdf;
};
std::once_flag initialized;

template <typename Load>
Document *openDocument(size_t extraBytes, Load load) {
    std::call_once(initialized, [] {
        FPDF_LIBRARY_CONFIG config{};
        config.version = 2;
        FPDF_InitLibraryWithConfig(&config);
    });
    auto *doc = static_cast<Document *>(std::malloc(sizeof(Document) + extraBytes));
    if (!doc) return nullptr;
    doc->pdf = load(doc + 1);
    if (doc->pdf) return doc;
    std::free(doc);
    return nullptr;
}
}

void *xn_pdf_open(const void *bytes, int length, const char *password) {
    if (!bytes || length <= 0) return nullptr;
    return openDocument(length, [=](void *storage) {
        std::memcpy(storage, bytes, length);
        return FPDF_LoadMemDocument64(storage, length, password);
    });
}

void *xn_pdf_open_file(const char *path, const char *password) {
    if (!path) return nullptr;
    return openDocument(0, [=](void *) { return FPDF_LoadDocument(path, password); });
}

int xn_pdf_count(void *handle) {
    return FPDF_GetPageCount(static_cast<Document *>(handle)->pdf);
}

int xn_pdf_size(void *handle, int index, double *width, double *height) {
    FS_SIZEF size{};
    if (!FPDF_GetPageSizeByIndexF(static_cast<Document *>(handle)->pdf, index, &size)) return 0;
    *width = size.width;
    *height = size.height;
    return 1;
}

int xn_pdf_render(void *handle, int index, int width, int height, int32_t *pixels) {
    if (!pixels || width <= 0 || height <= 0 || width > INT_MAX / 4 / height) return 0;
    FPDF_PAGE page = FPDF_LoadPage(static_cast<Document *>(handle)->pdf, index);
    if (!page) return 0;
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, pixels, width * 4);
    const bool ready = bitmap && FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xffffffff);
    if (ready) FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, FPDF_ANNOT);
    if (bitmap) FPDFBitmap_Destroy(bitmap);
    FPDF_ClosePage(page);
    return ready;
}

void xn_pdf_close(void *handle) {
    FPDF_CloseDocument(static_cast<Document *>(handle)->pdf);
    std::free(handle);
}
