package org.sterl.llmpeon.parts.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.sterl.llmpeon.parts.widget.FileChangeReviewWidget.FileChange;
import org.sterl.llmpeon.shared.AiMonitor.AiFileUpdate;

public class FileChangeReviewWidgetMergeTest {

  private static final String FILE = "/project/src/Foo.java";

  @Test
  public void firstChange_recordsUpdateAsIs() {
    var update = new AiFileUpdate(FILE, "a", "a'");
    var merged = FileChangeReviewWidget.mergeChange(FILE, null, update, null);
    assertEquals("a", merged.oldContent());
    assertEquals("a'", merged.newContent());
    assertNull(merged.restoreState());
  }

  @Test
  public void consecutiveAiEdits_keepOriginalBaseline() {
    var existing = new FileChange(FILE, "a", "a'", null);
    var update = new AiFileUpdate(FILE, "a'", "a''");
    var merged = FileChangeReviewWidget.mergeChange(FILE, existing, update, null);
    assertEquals("a", merged.oldContent());
    assertEquals("a''", merged.newContent());
  }

  @Test
  public void gapAfterManualEdit_resetsBaselineToPreEditState() {
    var existing = new FileChange(FILE, "a", "a'", null);
    var update = new AiFileUpdate(FILE, "a'+1", "(a'+1)'");
    var merged = FileChangeReviewWidget.mergeChange(FILE, existing, update, null);
    assertEquals("a'+1", merged.oldContent());
    assertEquals("(a'+1)'", merged.newContent());
  }
}
