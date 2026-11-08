package cn.wildfire.chat.kit.widget;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class GlassBackdropRendererTest {
    @Test public void uniformMaterialRetainsItsColor() {
        int[] source = {0xff345678, 0xff345678, 0xff345678, 0xff345678};
        int[] output = new int[4];
        GlassBackdropRenderer.boxPass(source, output, 2, 2, true);
        assertArrayEquals(source, output);
        GlassBackdropRenderer.boxPass(source, output, 2, 2, false);
        assertArrayEquals(source, output);
    }

    @Test public void blurReplicatesEdgePixelsInsteadOfAddingDarkBorders() {
        int[] source = {0xffff0000, 0xff000000, 0xff000000};
        int[] output = new int[3];
        GlassBackdropRenderer.boxPass(source, output, 3, 1, true);
        assertArrayEquals(new int[]{0xff990000, 0xff660000, 0xff330000}, output);
        GlassBackdropRenderer.boxPass(source, output, 1, 3, false);
        assertArrayEquals(new int[]{0xff990000, 0xff660000, 0xff330000}, output);
    }

    @Test public void horizontalPassDoesNotMixAdjacentRows() {
        int[] source = {0xffff0000, 0xffff0000, 0xff00ff00, 0xff00ff00};
        int[] output = new int[4];
        GlassBackdropRenderer.boxPass(source, output, 2, 2, true);
        assertArrayEquals(source, output);
    }

    @Test public void singlePixelRegionWorksInBothDirections() {
        int[] source = {0xff123456};
        int[] output = new int[1];
        GlassBackdropRenderer.boxPass(source, output, 1, 1, true);
        assertArrayEquals(source, output);
        GlassBackdropRenderer.boxPass(source, output, 1, 1, false);
        assertArrayEquals(source, output);
    }
}
