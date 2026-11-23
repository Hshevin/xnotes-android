@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.infinite.CanvasProjection
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import kotlinx.cinterop.*
import platform.Foundation.NSError
import platform.Metal.*

data class MetalViewport(val originX: Double, val originY: Double, val scale: Double)
data class MetalMesh(val mesh: MeshData, val color: Rgba)

class MetalInk private constructor(
    private val device: MTLDeviceProtocol,
    private val queue: MTLCommandQueueProtocol,
    private val pipeline: MTLRenderPipelineStateProtocol,
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
                MetalInk(device, checkNotNull(device.newCommandQueue()), pipeline, samples)
            }
        }
    }

    fun renderOffscreen(width: Int, height: Int, background: Rgba, viewport: MetalViewport, meshes: List<MetalMesh>): IntArray = autoreleasepool {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        require(viewport.originX.isFinite() && viewport.originY.isFinite() && viewport.scale.isFinite() && viewport.scale > 0.0)
        val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm, width.toULong(), height.toULong(), false)
        desc.usage = MTLTextureUsageRenderTarget
        desc.storageMode = MTLStorageModeShared
        val output = checkNotNull(device.newTextureWithDescriptor(desc))
        val pass = MTLRenderPassDescriptor()
        pass.colorAttachments.objectAtIndexedSubscript(0u).apply {
            if (samples > 1u) {
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
            encoder.setRenderPipelineState(pipeline)
            val uniforms = floatArrayOf(viewport.scale.toFloat(), CanvasProjection.MIN_HALF_WIDTH_PX.toFloat(), width.toFloat(), height.toFloat())
            uniforms.usePinned { encoder.setVertexBytes(it.addressOf(0), 16u, 1u) }
            for (mesh in meshes) if (!mesh.mesh.isEmpty) encode(encoder, mesh, viewport)
        } finally { encoder.endEncoding() }
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
            val argb = colors?.get(index) ?: base
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
"""
