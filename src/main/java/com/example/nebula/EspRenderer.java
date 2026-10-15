package com.example.nebula;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Draws Block ESP boxes / outlines / tracers with a custom pipeline that has NO depth test,
 * so everything is visible through walls. Based on the Fabric docs' "Rendering in the World" example.
 *
 * Outlines and tracers are drawn as thin camera-facing ribbons whose width grows with distance,
 * so they keep a constant on-screen thickness.
 */
public final class EspRenderer {
    private EspRenderer() {}

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(NebulaClient.MOD_ID, "pipeline/esp_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static BufferBuilder buffer;
    private static int vertexCount;

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    private static boolean failed;

    /** The camera as of the last world render (position + rotation). Used by Nametags to project onto the screen. */
    public static volatile boolean camValid;
    public static double camX, camY, camZ;
    public static final Quaternionf camRot = new Quaternionf();

    private static final double LINE_K = 0.0011;   // ribbon half-width per block of distance (~2px)

    public static void init() {
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(EspRenderer::onRender);
    }

    public static void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    // ------------------------------------------------------------ frame
    private static void onRender(WorldRenderContext ctx) {
        try {
            var cs = ctx.worldState().cameraRenderState;
            camX = cs.pos.x;
            camY = cs.pos.y;
            camZ = cs.pos.z;
            camRot.set(cs.orientation);
            camValid = true;
        } catch (Throwable ignored) {
            camValid = false;
        }
        if (failed) return;
        try {
            render(ctx);
        } catch (Throwable t) {
            failed = true;
            buffer = null;
            NebulaClient.LOGGER.error("Block ESP renderer hit an error and was switched off for this session", t);
        }
    }

    private static void render(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        BlockEspModule esp = NebulaClient.BLOCK_ESP;
        SusChunkFinderModule sus = NebulaClient.SUS_CHUNKS;
        LivePlayerDetectorModule live = NebulaClient.LIVE_PLAYERS;

        boolean blocksOn = esp != null && esp.isEnabled();
        boolean susOn = sus != null && sus.isEnabled() && sus.chunkCount > 0;
        boolean liveOn = false;
        if (live != null && live.isEnabled()) {
            live.collect(mc.level, mc.player);
            liveOn = live.chunkCount > 0;
        }

        Vec3 cam = ctx.worldState().cameraRenderState.pos;
        int n = blocksOn ? esp.collect(mc.level, cam.x, cam.y, cam.z) : 0;
        PlayerEspModule pe = NebulaClient.PLAYER_ESP;
        List<AbstractClientPlayer> players = (pe != null && pe.isEnabled())
                ? pe.collect(mc.level, mc.player) : List.of();
        if (n == 0 && !susOn && !liveOn && players.isEmpty()) return;

        // Camera-relative coordinates keep float precision good far from the origin.
        Matrix4fc m = ctx.matrices().last().pose();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 look = mc.player.getViewVector(partial);

        vertexCount = 0;
        buffer = new BufferBuilder(ALLOCATOR, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());

        // ---- Block ESP
        if (n > 0) {
            float op = esp.opacity();
            float fillA = 0.55f * op;
            float lineA = 0.55f + 0.45f * op;
            float tracerA = 0.35f + 0.65f * op;
            boolean tracers = esp.tracers();

            for (int i = 0; i < n; i++) {
                long p = esp.outPos[i];
                int col = esp.outColor[i];
                float r = ((col >> 16) & 0xFF) / 255f;
                float g = ((col >> 8) & 0xFF) / 255f;
                float b = (col & 0xFF) / 255f;

                double x0 = BlockPos.getX(p) - cam.x;
                double y0 = BlockPos.getY(p) - cam.y;
                double z0 = BlockPos.getZ(p) - cam.z;

                // transparent fill
                fillBox(m, (float) x0, (float) y0, (float) z0, (float) (x0 + 1), (float) (y0 + 1), (float) (z0 + 1), r, g, b, fillA);

                // solid outline: 12 edges (with thousands of blocks, only the nearest ones get one)
                if (esp.outD2[i] <= esp.outlineCutoff2) for (int c = 0; c < 8; c++) {
                    for (int bit = 1; bit <= 4; bit <<= 1) {
                        if ((c & bit) != 0) continue;
                        int d = c | bit;
                        edge(m,
                                x0 + (c & 1), y0 + ((c >> 1) & 1), z0 + ((c >> 2) & 1),
                                x0 + (d & 1), y0 + ((d >> 1) & 1), z0 + ((d >> 2) & 1),
                                r, g, b, lineA);
                    }
                }

                if (tracers) {
                    tracer(m, look.x * 0.6, look.y * 0.6, look.z * 0.6, x0 + 0.5, y0 + 0.5, z0 + 0.5, r, g, b, tracerA);
                }
            }
        }

        // ---- Player ESP
        if (!players.isEmpty()) {
            for (AbstractClientPlayer p : players) playerEsp(m, cam, p, partial, pe.mode(), pe.color(), pe.opacity());
        }

        // ---- chunk layers at Y 45: purple for suspicious chunks, rainbow for live players
        float time = (System.currentTimeMillis() % 5000L) / 5000f;
        if (susOn) {
            boolean lines = sus.tracers();
            for (int i = 0; i < sus.chunkCount; i++) {
                chunkLayer(m, cam, sus.chunks[i], false, time);
                if (lines) chunkTracer(m, cam, look, sus.chunks[i], false, time);
            }
        }
        if (liveOn) {
            boolean lines = live.tracers();
            for (int i = 0; i < live.chunkCount; i++) {
                chunkLayer(m, cam, live.chunks[i], true, time);
                if (lines) chunkTracer(m, cam, look, live.chunks[i], true, time);
            }
        }

        draw(mc);
    }

    private static final int PURPLE = 0xA03CF0;

    // ------------------------------------------------------------ Player ESP
    private static void boxEdges(Matrix4fc m, double x0, double y0, double z0, double x1, double y1, double z1,
                                 float r, float g, float b, float a) {
        for (int c = 0; c < 8; c++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((c & bit) != 0) continue;
                int d = c | bit;
                edge(m,
                        (c & 1) == 0 ? x0 : x1, (c & 2) == 0 ? y0 : y1, (c & 4) == 0 ? z0 : z1,
                        (d & 1) == 0 ? x0 : x1, (d & 2) == 0 ? y0 : y1, (d & 4) == 0 ? z0 : z1,
                        r, g, b, a);
            }
        }
    }

    private static void playerEsp(Matrix4fc m, Vec3 cam, AbstractClientPlayer p, float partial, int mode, int rgb, float opacity) {
        double x = Mth.lerp(partial, p.xo, p.getX()) - cam.x;
        double y = Mth.lerp(partial, p.yo, p.getY()) - cam.y;
        double z = Mth.lerp(partial, p.zo, p.getZ()) - cam.z;
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        float fillA = 0.45f * opacity;
        float lineA = 0.55f + 0.45f * opacity;
        double half = p.getBbWidth() / 2.0;
        double h = p.getBbHeight();

        if (mode == PlayerEspModule.SKELETON) {
            float bodyYaw = Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot);
            skeleton(m, x, y, z, bodyYaw, h, p.isCrouching(), r, g, b, lineA);
            return;
        }
        if (mode == PlayerEspModule.FILLED) {
            fillBox(m, (float) (x - half), (float) y, (float) (z - half),
                    (float) (x + half), (float) (y + h), (float) (z + half), r, g, b, fillA);
        }
        boxEdges(m, x - half, y, z - half, x + half, y + h, z + half, r, g, b, lineA);
    }

    /** A stick-figure: spine, head box, shoulders, arms, hips and legs, sized from the player's height. */
    private static void skeleton(Matrix4fc m, double px, double py, double pz, float bodyYawDeg, double height,
                                 boolean sneaking, float r, float g, float b, float a) {
        double yaw = Math.toRadians(bodyYawDeg);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);          // the player's right
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);           // the player's forward
        double hipY = height * 0.40;
        double shoulderY = height * 0.82;
        double headY = height * 0.92;
        double lean = sneaking ? 0.22 : 0.0;

        double hx = px, hy = py + hipY, hz = pz;                                         // hip centre
        double nx = px + fx * lean, ny = py + shoulderY, nz = pz + fz * lean;            // neck
        double cx = px + fx * lean * 1.15, cy = py + headY, cz = pz + fz * lean * 1.15;  // head centre

        double sh = 0.30, hh = 0.12, armLen = height * 0.36;
        double slx = nx - rx * sh, slz = nz - rz * sh, srx = nx + rx * sh, srz = nz + rz * sh;   // shoulders
        double hlx = hx - rx * hh, hlz = hz - rz * hh, hrx = hx + rx * hh, hrz = hz + rz * hh;   // hips

        edge(m, hx, hy, hz, nx, ny, nz, r, g, b, a);                                     // spine
        edge(m, nx, ny, nz, cx, cy - 0.25, cz, r, g, b, a);                              // neck
        edge(m, slx, ny, slz, srx, ny, srz, r, g, b, a);                                 // shoulder line
        edge(m, slx, ny, slz, slx - rx * 0.04, ny - armLen, slz - rz * 0.04, r, g, b, a);  // left arm
        edge(m, srx, ny, srz, srx + rx * 0.04, ny - armLen, srz + rz * 0.04, r, g, b, a);  // right arm
        edge(m, hlx, hy, hlz, hrx, hy, hrz, r, g, b, a);                                 // hip line
        edge(m, hlx, hy, hlz, hlx, py, hlz, r, g, b, a);                                 // left leg
        edge(m, hrx, hy, hrz, hrx, py, hrz, r, g, b, a);                                 // right leg
        boxEdges(m, cx - 0.25, cy - 0.25, cz - 0.25, cx + 0.25, cy + 0.25, cz + 0.25, r, g, b, a);   // head
    }

    private static int hue(float h) {
        return Mth.hsvToRgb(h - (float) Math.floor(h), 0.85f, 1.0f);
    }

    private static void vc(Matrix4fc m, double x, double y, double z, int rgb, float a) {
        v(m, x, y, z, ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, a);
    }

    private static void edgeC(Matrix4fc m, double ax, double ay, double az, double bx, double by, double bz, int rgb, float a) {
        edge(m, ax, ay, az, bx, by, bz, ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, a);
    }

    /** A tracer line from your crosshair to the middle of a marked chunk. */
    private static void chunkTracer(Matrix4fc m, Vec3 cam, Vec3 look, long key, boolean rainbow, float time) {
        int cx = (int) key;
        int cz = (int) (key >>> 32);
        double ex = cx * 16.0 + 8.0 - cam.x;
        double ey = SusChunkFinderModule.PLANE_Y - cam.y;
        double ez = cz * 16.0 + 8.0 - cam.z;
        int rgb = rainbow ? hue(time + (cx * 31 + cz * 17 & 0xFF) / 255f) : PURPLE;
        tracer(m, look.x * 0.6, look.y * 0.6, look.z * 0.6, ex, ey, ez,
                ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 0.9f);
    }

    /** A flat 16x16 layer over a whole chunk at Y 45, with a brighter border. Rainbow layers cycle colours over time. */
    private static void chunkLayer(Matrix4fc m, Vec3 cam, long key, boolean rainbow, float time) {
        int cx = (int) key;
        int cz = (int) (key >>> 32);
        double x0 = cx * 16.0 - cam.x;
        double z0 = cz * 16.0 - cam.z;
        double x1 = x0 + 16.0;
        double z1 = z0 + 16.0;
        double y = SusChunkFinderModule.PLANE_Y - cam.y;

        int c0, c1, c2, c3;
        if (rainbow) {
            c0 = hue(time);
            c1 = hue(time + 0.25f);
            c2 = hue(time + 0.5f);
            c3 = hue(time + 0.75f);
        } else {
            c0 = c1 = c2 = c3 = PURPLE;
        }
        float fillA = rainbow ? 0.32f : 0.28f;
        float lineA = 0.95f;

        // fill, written twice (both windings) so culling never hides it
        vc(m, x0, y, z0, c0, fillA); vc(m, x1, y, z0, c1, fillA); vc(m, x1, y, z1, c2, fillA); vc(m, x0, y, z1, c3, fillA);
        vc(m, x0, y, z1, c3, fillA); vc(m, x1, y, z1, c2, fillA); vc(m, x1, y, z0, c1, fillA); vc(m, x0, y, z0, c0, fillA);

        // border
        edgeC(m, x0, y, z0, x1, y, z0, c0, lineA);
        edgeC(m, x1, y, z0, x1, y, z1, c1, lineA);
        edgeC(m, x1, y, z1, x0, y, z1, c2, lineA);
        edgeC(m, x0, y, z1, x0, y, z0, c3, lineA);
    }

    // ------------------------------------------------------------ geometry
    private static void v(Matrix4fc m, double x, double y, double z, float r, float g, float b, float a) {
        buffer.addVertex(m, (float) x, (float) y, (float) z).setColor(r, g, b, a);
        vertexCount++;
    }

    /** One quad, written twice (both windings) so back-face culling never hides it. */
    private static void quad2(Matrix4fc m, double ax, double ay, double az, double bx, double by, double bz,
                              double cx, double cy, double cz, double dx, double dy, double dz,
                              float r, float g, float b, float a) {
        v(m, ax, ay, az, r, g, b, a);
        v(m, bx, by, bz, r, g, b, a);
        v(m, cx, cy, cz, r, g, b, a);
        v(m, dx, dy, dz, r, g, b, a);
        v(m, dx, dy, dz, r, g, b, a);
        v(m, cx, cy, cz, r, g, b, a);
        v(m, bx, by, bz, r, g, b, a);
        v(m, ax, ay, az, r, g, b, a);
    }

    private static void edge(Matrix4fc m, double ax, double ay, double az, double bx, double by, double bz,
                             float r, float g, float b, float a) {
        double ex = bx - ax, ey = by - ay, ez = bz - az;
        double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
        // ribbon faces the camera (which sits at the origin): side = edge x midpoint
        double sx = ey * mz - ez * my;
        double sy = ez * mx - ex * mz;
        double sz = ex * my - ey * mx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        double dist = Math.sqrt(mx * mx + my * my + mz * mz);
        double h = Math.max(0.006, LINE_K * dist);
        sx = sx / sl * h; sy = sy / sl * h; sz = sz / sl * h;
        double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
        double ux = ex / el * h, uy = ey / el * h, uz = ez / el * h;   // extend ends to close the corners
        quad2(m,
                ax - ux + sx, ay - uy + sy, az - uz + sz,
                bx + ux + sx, by + uy + sy, bz + uz + sz,
                bx + ux - sx, by + uy - sy, bz + uz - sz,
                ax - ux - sx, ay - uy - sy, az - uz - sz,
                r, g, b, a);
    }

    private static void tracer(Matrix4fc m, double ax, double ay, double az, double bx, double by, double bz,
                               float r, float g, float b, float a) {
        double ex = bx - ax, ey = by - ay, ez = bz - az;
        double mx = (ax + bx) * 0.5, my = (ay + by) * 0.5, mz = (az + bz) * 0.5;
        double sx = ey * mz - ez * my;
        double sy = ez * mx - ex * mz;
        double sz = ex * my - ey * mx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        sx /= sl; sy /= sl; sz /= sl;
        double hA = Math.max(0.0008, LINE_K * 0.8 * Math.sqrt(ax * ax + ay * ay + az * az));
        double hB = Math.max(0.006, LINE_K * 0.8 * Math.sqrt(bx * bx + by * by + bz * bz));
        quad2(m,
                ax + sx * hA, ay + sy * hA, az + sz * hA,
                bx + sx * hB, by + sy * hB, bz + sz * hB,
                bx - sx * hB, by - sy * hB, bz - sz * hB,
                ax - sx * hA, ay - sy * hA, az - sz * hA,
                r, g, b, a);
    }

    private static void fillBox(Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                float r, float g, float b, float a) {
        // Same face order as the Fabric docs example (outward-facing).
        // Front
        v(m, minX, minY, maxZ, r, g, b, a); v(m, maxX, minY, maxZ, r, g, b, a);
        v(m, maxX, maxY, maxZ, r, g, b, a); v(m, minX, maxY, maxZ, r, g, b, a);
        // Back
        v(m, maxX, minY, minZ, r, g, b, a); v(m, minX, minY, minZ, r, g, b, a);
        v(m, minX, maxY, minZ, r, g, b, a); v(m, maxX, maxY, minZ, r, g, b, a);
        // Left
        v(m, minX, minY, minZ, r, g, b, a); v(m, minX, minY, maxZ, r, g, b, a);
        v(m, minX, maxY, maxZ, r, g, b, a); v(m, minX, maxY, minZ, r, g, b, a);
        // Right
        v(m, maxX, minY, maxZ, r, g, b, a); v(m, maxX, minY, minZ, r, g, b, a);
        v(m, maxX, maxY, minZ, r, g, b, a); v(m, maxX, maxY, maxZ, r, g, b, a);
        // Top
        v(m, minX, maxY, maxZ, r, g, b, a); v(m, maxX, maxY, maxZ, r, g, b, a);
        v(m, maxX, maxY, minZ, r, g, b, a); v(m, minX, maxY, minZ, r, g, b, a);
        // Bottom
        v(m, minX, minY, minZ, r, g, b, a); v(m, maxX, minY, minZ, r, g, b, a);
        v(m, maxX, minY, maxZ, r, g, b, a); v(m, minX, minY, maxZ, r, g, b, a);
    }

    // ------------------------------------------------------------ drawing phase
    private static void draw(Minecraft client) {
        if (vertexCount == 0) {
            buffer = null;
            return;
        }
        MeshData built = buffer.buildOrThrow();
        MeshData.DrawState state = built.drawState();
        VertexFormat format = state.format();

        // upload vertices
        int size = state.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> NebulaClient.MOD_ID + " esp vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size + size / 2);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, built.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(built.vertexBuffer(), view.data());
        }
        GpuBuffer vertices = vertexBuffer.currentBuffer();

        // plain sequential quad indices (no per-frame sorting)
        RenderSystem.AutoStorageIndexBuffer shapeIndices = RenderSystem.getSequentialBuffer(PIPELINE.getVertexFormatMode());
        GpuBuffer indices = shapeIndices.getBuffer(state.indexCount());
        VertexFormat.IndexType indexType = shapeIndices.type();

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> NebulaClient.MOD_ID + " esp pass",
                client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0, 0, state.indexCount(), 1);
        }

        built.close();
        vertexBuffer.rotate();
        buffer = null;
    }
}
