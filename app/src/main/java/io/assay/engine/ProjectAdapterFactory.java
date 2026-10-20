package io.assay.engine;

import io.assay.model.ProjectAdapter;

/**
 * The extension point a domain project implements.
 *
 * <p>The four capabilities are supplied by a compiled class that is named on the command line,
 * packaged in a jar and registered through {@code ServiceLoader}, or referenced from the classpath.
 */
public interface ProjectAdapterFactory {

    ProjectAdapter create();
}
