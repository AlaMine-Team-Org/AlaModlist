package day.alacraft.alamodlist.archfixture;

import net.neoforged.archfixture.FakeLoaderType;

/** Breaks SHARED_CODE_IS_LOADER_NEUTRAL on purpose: shared code naming a loader type. Never run. */
public final class LoaderApiViolator {

    private FakeLoaderType loaderType;

    public FakeLoaderType loaderType() {
        return loaderType;
    }
}
