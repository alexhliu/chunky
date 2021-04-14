package se.llbit.chunky.block;

import se.llbit.chunky.model.BlockModel;
import se.llbit.chunky.model.SeaPickleModel;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.Texture;
import se.llbit.math.Ray;

public class SeaPickle extends MinecraftBlockTranslucent implements ModelBlock {
  private final SeaPickleModel model;
  private final String description;

  public SeaPickle(int pickles, boolean live) {
    super("sea_pickle", Texture.seaPickle);
    pickles = Math.max(1, Math.min(4, pickles));
    this.description = String.format("pickles=%d", pickles);
    this.model = new SeaPickleModel(pickles, live);
    localIntersect = true;
  }

  @Override
  public boolean intersect(Ray ray, Scene scene) {
    return model.intersect(ray, scene);
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public BlockModel getModel() {
    return model;
  }
}
