package day.alacraft.alamodlist.archfixture;

import org.lwjgl.opengl.GL11;

/** Breaks RENDERING_STAYS_BACKEND_AGNOSTIC on purpose: a direct OpenGL call. Never run. */
public final class BackendViolator {

    public static void clear() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }
}
