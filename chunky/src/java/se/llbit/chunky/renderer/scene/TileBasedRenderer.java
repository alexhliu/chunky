package se.llbit.chunky.renderer.scene;

import it.unimi.dsi.fastutil.ints.IntIntMutablePair;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.renderer.InternalRenderManager;
import se.llbit.chunky.renderer.Renderer;
import se.llbit.chunky.renderer.WorkerState;
import se.llbit.math.Ray;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * A tile based renderer. Simply call {@code submitTiles} to submit a frame's worth of tiles to the work queue.
 * Call {@code manager.pool.awaitEmpty()} to block until all tiles are finished rendering.
 * Call {@code postRender.getAsBoolean()} after each frame (and terminate if it returns {@code true}).
 *
 * Implementation detail: Tiles are cached for faster rendering.
 */
public abstract class TileBasedRenderer implements Renderer {
  protected BooleanSupplier postRender = () -> true;

  private final ArrayList<RenderTile> cachedTiles = new ArrayList<>();
  private int prevWidth = -1;
  private int prevHeight = -1;

  public static class RenderTile {
    public int x0, x1;
    public int y0, y1;

    public RenderTile(int x0, int x1, int y0, int y1) {
      this.x0 = x0;
      this.x1 = x1;
      this.y0 = y0;
      this.y1 = y1;
    }
  }

  /**
   * Set the post-render callback. This should be called after each frame is complete.
   * Generally the render loop will look like:
   * {@code
   *   while (scene.spp < scene.getTargetSpp()) {
   *     submitTiles(manager, (state, pixel) -> {});
   *     manager.pool.awaitEmpty();
   *     scene.spp += 1; // update spp
   *     if (postRender.getAsBoolean()) return;
   *   }
   * }
   */
  @Override
  public void setPostRender(BooleanSupplier callback) {
    postRender = callback;
  }

  /**
   * Create and submit tiles to the rendering pool.
   * Await for these tiles to finish rendering with {@code manager.pool.awaitEmpty()}.
   *
   * @param perPixel This is called on every pixel. The first argument is the worker state.
   *                 The second argument is the current pixel (x, y).
   */
  protected void submitTiles(InternalRenderManager manager, BiConsumer<WorkerState, IntIntPair> perPixel) {
    initTiles(manager);

    cachedTiles.forEach(tile ->
        manager.pool.submit(worker -> {
          WorkerState state = new WorkerState();
          state.ray = new Ray();
          state.random = worker.random;

          IntIntMutablePair pair = new IntIntMutablePair(0, 0);

          for (int i = tile.x0; i < tile.x1; i++) {
            for (int j = tile.y0; j < tile.y1; j++) {
              pair.left(i).right(j);
              perPixel.accept(state, pair);
            }
          }
        })
    );
  }

  private void initTiles(InternalRenderManager manager) {
    Scene bufferedScene = manager.bufferedScene;
    int width = bufferedScene.width;
    int height = bufferedScene.height;
    int tileWidth = manager.context.tileWidth();

    if (prevWidth != width || prevHeight != height) {
      prevWidth = width;
      prevHeight = height;
      cachedTiles.clear();

      for (int i = 0; i < width; i += tileWidth) {
        for (int j = 0; j < height; j += tileWidth) {
          cachedTiles.add(new RenderTile(i, FastMath.min(i + tileWidth, width),
              j, FastMath.min(j + tileWidth, height)));
        }
      }
    }
  }
}