package io.suko.ext;

import io.suko.lang.project.ProjectView;

public record ProjectEmitContext(ProjectView project, SecurityOptions options) {
}
