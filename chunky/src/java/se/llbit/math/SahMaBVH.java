/* Copyright (c) 2021 Jesper Öqvist <jesper@llbit.se>
 * Copyright (c) 2021 Chunky contributors
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.math;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import org.apache.commons.math3.util.FastMath;
import se.llbit.chunky.entity.Entity;
import se.llbit.chunky.main.Chunky;
import se.llbit.log.Log;
import se.llbit.math.primitive.MutableAABB;
import se.llbit.math.primitive.Primitive;
import se.llbit.util.TaskTracker;

import java.util.*;
import java.util.function.IntConsumer;

import static se.llbit.math.BVH.SPLIT_LIMIT;

public class SahMaBVH extends BinaryBVH {
    public static void initImplementation() {
        BVH.factories.put("SAH_MA", new BVH.ImplementationFactory() {
            @Override
            public BVH.BVHImplementation create(Collection<Entity> entities, Vector3 worldOffset, TaskTracker.Task task) {
                task.update(1000, 0);
                double entityScaler = 500.0 / entities.size();
                int done = 0;

                List<Primitive> primitives = new ArrayList<>(entities.size());
                for (Entity entity : entities) {
                    for (Primitive prim : entity.primitives(worldOffset)) {
                        primitives.add(prim.pack());
                    }

                    done++;
                    task.updateInterval((int) (done * entityScaler), 1);
                }
                Primitive[] allPrimitives = primitives.toArray(new Primitive[0]);
                primitives = null; // Allow the collection to be garbage collected during construction when only the array is used

                double primitiveScaler = 500.0 / allPrimitives.length;
                return new SahMaBVH(allPrimitives, i -> task.updateInterval((int) (i * primitiveScaler) + 500, 1));
            }

            @Override
            public String getTooltip() {
                return "Fast and nearly optimal BVH building method.";
            }
        });
    }

    public SahMaBVH(Primitive[] primitives, IntConsumer task) {
        IntArrayList data = new IntArrayList(primitives.length);
        ArrayList<Primitive> packedPrimitives = new ArrayList<>(primitives.length);

        this.depth = constructSAH_MA(primitives, task, data, packedPrimitives, 0, primitives.length);
        this.packed = data.toIntArray();
        this.packedPrimitives = packedPrimitives.toArray(new Primitive[0]);
        Log.info("Built SAH_MA BVH with depth: " + this.depth);
    }

    /**
     * Construct a BVH using Surface Area Heuristic (SAH)
     * This splits along the major axis which usually gets good results.
     */
    private int constructSAH_MA(Primitive[] primitives, IntConsumer task, IntArrayList data, ArrayList<Primitive> packedPrimitives, int start, int end) {
        int index = data.size();
        data.add(0);
        data.add(0);
        AABB bb = bb(primitives, start, end);
        packAabb(bb, data);

        if (end - start < SPLIT_LIMIT) {
            data.set(index, -packedPrimitives.size());
            data.set(index+1, end-start);
            Collections.addAll(packedPrimitives, Arrays.copyOfRange(primitives, start, end));
            Arrays.fill(primitives, start, end, null);  // Fill with null to allow for GC
            return 1;
        }

        int split = splitSAH_MA(primitives, bb, start, end);
        int depth1 = constructSAH_MA(primitives, task, data, packedPrimitives, start, split);
        data.set(index, data.size());
        int depth2 = constructSAH_MA(primitives, task, data, packedPrimitives, split, end);

        task.accept(end);
        return FastMath.max(depth1, depth2)+1;
    }

    /**
     * Calculate the best split point using the Surface Area Heuristics along the major axis.
     */
    private int splitSAH_MA(Primitive[] primitives, AABB bb, int start, int end) {
        double xl = bb.xmax - bb.xmin;
        double yl = bb.ymax - bb.ymin;
        double zl = bb.zmax - bb.zmin;
        Comparator<Primitive> cmp;
        if (xl >= yl && xl >= zl) {
            cmp = cmpX;
        } else if (yl >= xl && yl >= zl) {
            cmp = cmpY;
        } else {
            cmp = cmpZ;
        }

        MutableAABB bounds = new MutableAABB(0, 0, 0, 0, 0, 0);
        double cmin = Double.POSITIVE_INFINITY;
        int split = 0;

        double[] sl = new double[end-start];
        double[] sr = new double[end-start];

        if (end - start > PARALLEL_SORT_THRESHOLD) {
            Chunky.getCommonThreads().submit(() -> Arrays.parallelSort(primitives, start, end, cmp)).join();
        } else {
            Arrays.sort(primitives, start, end, cmp);
        }
        for (int i = 0; i < sl.length; ++i) {
            bounds.expand(primitives[start+i].bounds());
            sl[i] = bounds.surfaceArea();
        }
        bounds = new MutableAABB(0, 0, 0, 0, 0, 0);
        for (int i = sr.length-1; i > 0; --i) {
            bounds.expand(primitives[start+i].bounds());
            sr[i-1] = bounds.surfaceArea();
        }
        for (int i = 0; i < sl.length - 1; ++i) {
            double c = sl[i] * (i + 1) + sr[i] * (sl.length - i - 1);
            if (c < cmin) {
                cmin = c;
                split = i;
            }
        }

        return start+split+1;
    }

    private AABB bb(Primitive[] primitives, int start, int end) {
        double xmin = Double.POSITIVE_INFINITY;
        double xmax = Double.NEGATIVE_INFINITY;
        double ymin = Double.POSITIVE_INFINITY;
        double ymax = Double.NEGATIVE_INFINITY;
        double zmin = Double.POSITIVE_INFINITY;
        double zmax = Double.NEGATIVE_INFINITY;

        for (int i = start; i < end; i++) {
            AABB bb = primitives[i].bounds();
            if (bb.xmin < xmin)
                xmin = bb.xmin;
            if (bb.xmax > xmax)
                xmax = bb.xmax;
            if (bb.ymin < ymin)
                ymin = bb.ymin;
            if (bb.ymax > ymax)
                ymax = bb.ymax;
            if (bb.zmin < zmin)
                zmin = bb.zmin;
            if (bb.zmax > zmax)
                zmax = bb.zmax;
        }
        return new AABB(xmin, xmax, ymin, ymax, zmin, zmax);
    }
}
