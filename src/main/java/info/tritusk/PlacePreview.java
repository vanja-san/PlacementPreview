/*
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * 
 * For more information, please refer to <http://unlicense.org/>
 */

// SPDX-License-Identifier: Unlicense

package info.tritusk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.IVertexBuilder;

import org.lwjgl.glfw.GLFW;

import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.model.data.EmptyModelData;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("placement_preview")
public final class PlacePreview {

    private static final MethodHandle GET_STATE_FOR_PLACEMENT;

    static {
        MethodHandle m = null;
        try {
            m = MethodHandles.lookup().unreflect(ObfuscationReflectionHelper.findMethod(BlockItem.class, "func_195945_b", BlockItemUseContext.class));
        } catch (Exception ignored) {
            // nothing we can do here, really
        }
        GET_STATE_FOR_PLACEMENT = m;
    }

    private static IRenderTypeBuffer.Impl renderBuffer = null;

    private static KeyBinding enablePreviewHotkey;

    private static boolean enable = true;

    public PlacePreview() {
        DistExecutor.runWhenOn(Dist.CLIENT, () -> () -> {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(PlacePreview::setup);
            MinecraftForge.EVENT_BUS.register(PlacePreview.class);
        });
    }

    private static void setup(FMLClientSetupEvent event) {
        ClientRegistry.registerKeyBinding(enablePreviewHotkey = new KeyBinding("key.placement_preview.toggle", KeyConflictContext.IN_GAME, KeyModifier.SHIFT, InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.category.placement_preview"));
    }

    @SubscribeEvent
    public static void hotkey(InputEvent.KeyInputEvent event) {
        if (enablePreviewHotkey.isPressed()) {
            enable = !enable;
        }
    }

    /*
     * The following code is a modified version of multiblock visualization code from the Patchouli mod, 
     * whose source locates at:
     * https://github.com/Vazkii/Patchouli/blob/master/src/main/java/vazkii/patchouli/client/handler/MultiblockVisualizationHandler.java
     */

    @SubscribeEvent
    public static void preview(RenderWorldLastEvent event) {
        if (!enable) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.objectMouseOver instanceof BlockRayTraceResult && mc.objectMouseOver.getType() != RayTraceResult.Type.MISS) {
            BlockRayTraceResult rayTrace = (BlockRayTraceResult) mc.objectMouseOver;
            PlayerEntity player = mc.player;
            ItemStack held = player.getHeldItemMainhand();
            if (held.getItem() instanceof BlockItem) {
                BlockItem theBlockItem = (BlockItem) held.getItem();
                BlockItemUseContext context = theBlockItem.getBlockItemUseContext(new BlockItemUseContext(new ItemUseContext(player, Hand.MAIN_HAND, rayTrace)));
                if (context != null) {
                    BlockState placeResult;
                    try {
                        placeResult = (BlockState) GET_STATE_FOR_PLACEMENT.invokeExact(theBlockItem, context);
                    } catch (Throwable e) {
                        placeResult = null;
                    }
                    if (placeResult != null) {
                        if (renderBuffer == null) {
                            renderBuffer = initRenderBuffer(mc.getRenderTypeBuffers().getBufferSource());
                        }
                        MatrixStack transforms = event.getMatrixStack();
                        Vec3d projVec = mc.getRenderManager().info.getProjectedView();
                        transforms.translate(-projVec.x, -projVec.y, -projVec.z);
                        transforms.push();
                        BlockPos target = context.getPos();
                        transforms.translate(target.getX(), target.getY(), target.getZ());
                        World world = context.getWorld();
                        switch (placeResult.getRenderType()) {
                            case MODEL:
                                mc.getBlockRendererDispatcher().renderModel(placeResult, target, world, transforms, renderBuffer.getBuffer(RenderTypeLookup.getRenderType(placeResult)), EmptyModelData.INSTANCE);
                            case ENTITYBLOCK_ANIMATED:
                                /* 
                                 * Yes, we use a fake tile entity to workaround this.
                                 * All exceptions are discared. It is ugly, yes, but
                                 * it partially solve the problem.
                                 */
                                if (placeResult.hasTileEntity()) {
                                    TileEntity tile = placeResult.createTileEntity(world);
                                    tile.setWorldAndPos(world, target);
                                    TileEntityRenderer<? super TileEntity> renderer = TileEntityRendererDispatcher.instance.getRenderer(tile);
                                    if (renderer != null) {
                                        try {
                                            renderer.render(tile, 0F, transforms, renderBuffer, 0xF000F0, OverlayTexture.NO_OVERLAY);
                                        } catch (Exception ignored) {}
                                    }
                                }
                            default:
                                break;
                        }
                        transforms.pop();
                        renderBuffer.finish();
                    }
                }
            }
        }
    }

    private static IRenderTypeBuffer.Impl initRenderBuffer(IRenderTypeBuffer.Impl original) {
        BufferBuilder fallback = ObfuscationReflectionHelper.getPrivateValue(IRenderTypeBuffer.Impl.class, original, "field_228457_a_");
        Map<RenderType, BufferBuilder> layerBuffers = ObfuscationReflectionHelper.getPrivateValue(IRenderTypeBuffer.Impl.class, original, "field_228458_b_");
        Map<RenderType, BufferBuilder> remapped = new HashMap<>();
        for (Map.Entry<RenderType, BufferBuilder> e : layerBuffers.entrySet()) {
            remapped.put(GhostRenderType.remap(e.getKey()), e.getValue());
        }
        return new IRenderTypeBuffer.Impl(fallback, remapped) {
            @Override
            public IVertexBuilder getBuffer(RenderType type) {
                return super.getBuffer(GhostRenderType.remap(type));
            }
        };
    }

    private static class GhostRenderType extends RenderType {
        private static Map<RenderType, RenderType> remappedTypes = new IdentityHashMap<>();

        GhostRenderType(RenderType original) {
            super(original.toString() + "_place_preview", original.getVertexFormat(), original.getDrawMode(), original.getBufferSize(), original.isUseDelegate(), true, () -> {
                original.setupRenderState();
                RenderSystem.disableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.CONSTANT_ALPHA, GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA);
                RenderSystem.blendColor(1F, 1F, 1F, 0.5F);
            }, () -> {
                RenderSystem.blendColor(1F, 1F, 1F, 1F);
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
                RenderSystem.enableDepthTest();
                original.clearRenderState();
            });
        }

        public static RenderType remap(RenderType type) {
            return type instanceof GhostRenderType ? type : remappedTypes.computeIfAbsent(type, GhostRenderType::new);
        }
    }

}