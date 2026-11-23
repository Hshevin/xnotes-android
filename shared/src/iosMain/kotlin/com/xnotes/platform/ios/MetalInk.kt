@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import kotlinx.cinterop.*
import platform.Foundation.NSError
import platform.Metal.*
import platform.QuartzCore.CAMetalLayer

data class MetalViewport(val originX: Double, val originY: Double, val scale: Double)
sealed interface MetalPart
data class MetalMesh(val mesh: MeshData, val color: Rgba, val nightInk: Boolean = false) : MetalPart
class MetalPicture internal constructor(internal val texture: MTLTextureProtocol, val bounds: Rect) : MetalPart

class MetalInk private constructor(
    private val device: MTLDeviceProtocol,
    private val queue: MTLCommandQueueProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
    private val pictures: MTLRenderPipelineStateProtocol,
    private val samples: ULong,
) {
    companion object {
        fun createOrNull(): MetalInk? {
            val device = MTLCreateSystemDefaultDevice() ?: return null
            return memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val library = checkNotNull(device.newLibraryWithSource(INK_SOURCE, null, error.ptr)) {
                    "Metal shader compilation failed: ${error.value?.localizedDescription}"
                }
                val samples = if (device.supportsTextureSampleCount(4u)) 4uL else 1uL
                val desc = MTLRenderPipelineDescriptor().apply {
                    vertexFunction = checkNotNull(library.newFunctionWithName("inkVertex"))
                    fragmentFunction = checkNotNull(library.newFunctionWithName("inkFragment"))
                    rasterSampleCount = samples
                    colorAttachments.objectAtIndexedSubscript(0u).apply {
                        pixelFormat = MTLPixelFormatBGRA8Unorm
                        blendingEnabled = true
                        sourceRGBBlendFactor = MTLBlendFactorSourceAlpha
                        destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha
                        sourceAlphaBlendFactor = MTLBlendFactorOne
                        destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha
                    }
                }
                val pipeline = checkNotNull(device.newRenderPipelineStateWithDescriptor(desc, error.ptr)) {
                    "Metal pipeline creation failed: ${error.value?.localizedDescription}"
                }
                desc.vertexFunction = checkNotNull(library.newFunctionWithName("pictureVertex"))
                desc.fragmentFunction = checkNotNull(library.newFunctionWithName("pictureFragment"))
                val pictures = checkNotNull(device.newRenderPipelineStateWithDescriptor(desc, error.ptr))
                MetalInk(device, checkNotNull(device.newCommandQueue()), pipeline, pictures, samples)
            }
        }
    }

    fun renderOffscreen(width: Int, height: Int, background: Rgba, viewport: MetalViewport, meshes: List<MetalPart>): IntArray = autoreleasepool {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        require(viewport.originX.isFinite() && viewport.originY.isFinite() && viewport.scale.isFinite() && viewport.scale > 0.0)
        val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm, width.toULong(), height.toULong(), false)
        desc.usage = MTLTextureUsageRenderTarget
        desc.storageMode = MTLStorageModeShared
        val output = checkNotNull(device.newTextureWithDescriptor(desc))
        val command = encodeFrame(output, background, viewport, meshes)
        command.commit()
        command.waitUntilCompleted()
        check(command.status == MTLCommandBufferStatusCompleted) { "Metal render failed: ${command.error?.localizedDescription}" }
        val pixels = IntArray(width * height)
        pixels.usePinned {
            output.getBytes(it.addressOf(0), width.toULong() * 4u, MTLRegionMake2D(0u, 0u, width.toULong(), height.toULong()), 0u)
        }
        for (index in pixels.indices) {
            val argb = pixels[index]
            val alpha = argb ushr 24
            if (alpha != 255) pixels[index] = (alpha shl 24) or (straight((argb ushr 16) and 255, alpha) shl 16) or
                (straight((argb ushr 8) and 255, alpha) shl 8) or straight(argb and 255, alpha)
        }
        pixels
    }

    fun attach(layer: CAMetalLayer) {
        layer.device = device as objcnames.protocols.MTLDeviceProtocol
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm
    }

    fun present(layer: CAMetalLayer, background: Rgba, viewport: MetalViewport, parts: List<MetalPart>) = autoreleasepool {
        val drawable = layer.nextDrawable() ?: return@autoreleasepool
        val command = encodeFrame(drawable.texture as MTLTextureProtocol, background, viewport, parts)
        command.presentDrawable(drawable)
        command.commit()
    }

    fun picture(surface: CgSurface, bounds: Rect): MetalPicture {
        val width = surface.width.toULong(); val height = surface.height.toULong()
        val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatRGBA8Unorm, width, height, false)
        desc.usage = MTLTextureUsageShaderRead
        desc.storageMode = MTLStorageModeShared
        val texture = checkNotNull(device.newTextureWithDescriptor(desc))
        texture.replaceRegion(MTLRegionMake2D(0u, 0u, width, height), 0u, surface.bytes, width * 4u)
        return MetalPicture(texture, bounds)
    }

    private fun encodeFrame(output: MTLTextureProtocol, background: Rgba, viewport: MetalViewport,
                            parts: List<MetalPart>): MTLCommandBufferProtocol {
        val width = output.width; val height = output.height
        val pass = MTLRenderPassDescriptor()
        pass.colorAttachments.objectAtIndexedSubscript(0u).apply {
            if (samples > 1u) {
                val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm, width, height, false)
                desc.usage = MTLTextureUsageRenderTarget
                desc.textureType = MTLTextureType2DMultisample
                desc.sampleCount = samples
                desc.storageMode = MTLStorageModePrivate
                texture = checkNotNull(device.newTextureWithDescriptor(desc))
                resolveTexture = output
                storeAction = MTLStoreActionMultisampleResolve
            } else {
                texture = output
                storeAction = MTLStoreActionStore
            }
            loadAction = MTLLoadActionClear
            val alpha = background.a / 255.0
            clearColor = MTLClearColorMake(background.r * alpha / 255, background.g * alpha / 255, background.b * alpha / 255, alpha)
        }
        val command = checkNotNull(queue.commandBuffer())
        val encoder = checkNotNull(command.renderCommandEncoderWithDescriptor(pass))
        try {
            val uniforms = floatArrayOf(viewport.scale.toFloat(), CanvasProjection.MIN_HALF_WIDTH_PX.toFloat(), width.toFloat(), height.toFloat())
            uniforms.usePinned { encoder.setVertexBytes(it.addressOf(0), 16u, 1u) }
            for (part in parts) when (part) {
                is MetalMesh -> if (!part.mesh.isEmpty) {
                    encoder.setRenderPipelineState(pipeline)
                    encode(encoder, part, viewport)
                }
                is MetalPicture -> {
                    encoder.setRenderPipelineState(pictures)
                    val box = part.bounds
                    val rect = floatArrayOf((box.x - viewport.originX).toFloat(), (box.y - viewport.originY).toFloat(), box.w.toFloat(), box.h.toFloat())
                    rect.usePinned { encoder.setVertexBytes(it.addressOf(0), 16u, 0u) }
                    encoder.setFragmentTexture(part.texture, 0u)
                    encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, 0u, 4u)
                }
            }
        } finally { encoder.endEncoding() }
        return command
    }

    private fun encode(encoder: MTLRenderCommandEncoderProtocol, entry: MetalMesh, viewport: MetalViewport) {
        val mesh = entry.mesh
        val count = mesh.vertexCount
        require(mesh.positions.size % 2 == 0 && mesh.offsets.size == mesh.positions.size && count <= Int.MAX_VALUE / 8)
        require(mesh.indices.size % 3 == 0 && mesh.indices.all { it in 0 until count })
        val vertices = FloatArray(count * 8)
        val base = entry.color.toArgb()
        val colors = mesh.colors?.takeIf { it.size >= count }
        for (index in 0 until count) {
            val at = index * 8
            vertices[at] = (mesh.positions[2 * index] - viewport.originX).toFloat()
            vertices[at + 1] = (mesh.positions[2 * index + 1] - viewport.originY).toFloat()
            vertices[at + 2] = mesh.offsets[2 * index].toFloat()
            vertices[at + 3] = mesh.offsets[2 * index + 1].toFloat()
            val stored = colors?.get(index) ?: base
            val argb = if (entry.nightInk && (stored and 0xffffff) == 0) stored or 0xffffff else stored
            vertices[at + 4] = ((argb ushr 16) and 255) / 255f
            vertices[at + 5] = ((argb ushr 8) and 255) / 255f
            vertices[at + 6] = (argb and 255) / 255f
            vertices[at + 7] = (argb ushr 24) / 255f
        }
        val vertexBuffer = vertices.usePinned { checkNotNull(device.newBufferWithBytes(it.addressOf(0), vertices.size.toULong() * 4u, MTLResourceStorageModeShared)) }
        val indices = mesh.indices
        val indexBuffer = indices.usePinned { checkNotNull(device.newBufferWithBytes(it.addressOf(0), indices.size.toULong() * 4u, MTLResourceStorageModeShared)) }
        encoder.setVertexBuffer(vertexBuffer, 0u, 0u)
        encoder.drawIndexedPrimitives(MTLPrimitiveTypeTriangle, indices.size.toULong(), MTLIndexTypeUInt32, indexBuffer, 0u)
    }
}

private const val INK_SOURCE = """
#include <metal_stdlib>
using namespace metal;
struct Vertex { float2 position; float2 offset; float4 color; };
struct Varying { float4 position [[position]]; float4 color; };
vertex Varying inkVertex(uint id [[vertex_id]], const device Vertex *vertices [[buffer(0)]],
                         constant float4 &view [[buffer(1)]]) {
    Vertex ink = vertices[id];
    float2 point = ink.position * view.x;
    float2 spine = ink.offset * view.x;
    float reach = length(spine);
    if (reach > 0 && reach < view.y) {
        point += spine * (view.y / reach - 1);
        ink.color.a *= reach / view.y;
    }
    return {float4(2 * point.x / view.z - 1, 1 - 2 * point.y / view.w, 0, 1), ink.color};
}
fragment float4 inkFragment(Varying ink [[stage_in]]) { return ink.color; }
struct PictureVertex { float4 position [[position]]; float2 uv; };
vertex PictureVertex pictureVertex(uint id [[vertex_id]], constant float4 &box [[buffer(0)]], constant float4 &view [[buffer(1)]]) {
    float2 uv = float2(id & 1, id >> 1);
    float2 point = (box.xy + uv * box.zw) * view.x;
    return {float4(2 * point.x / view.z - 1, 1 - 2 * point.y / view.w, 0, 1), uv};
}
fragment float4 pictureFragment(PictureVertex vertexIn [[stage_in]], texture2d<float> image [[texture(0)]]) {
    constexpr sampler filter(coord::normalized, address::clamp_to_edge, filter::linear);
    float4 p = image.sample(filter, vertexIn.uv);
    return float4(p.rgb / max(p.a, 0.00001f), p.a);
}
"""
