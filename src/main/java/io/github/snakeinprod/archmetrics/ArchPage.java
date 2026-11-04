package io.github.snakeinprod.archmetrics;

import org.sonar.api.web.page.Context;
import org.sonar.api.web.page.Page;
import org.sonar.api.web.page.PageDefinition;

/** The project page "Architecture metrics" ({@code static/architecture.js}). */
public class ArchPage implements PageDefinition {

  @Override
  public void define(Context context) {
    context.addPage(Page.builder("archmetrics/architecture").setName("Architecture metrics").setScope(Page.Scope.COMPONENT)
      .setComponentQualifiers(Page.Qualifier.PROJECT).build());
  }
}
