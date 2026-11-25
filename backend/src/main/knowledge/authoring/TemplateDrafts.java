package knowledge.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import shared.web.ApiException;

/** UC-15 plan template drafts (lifestyle.templates). A template a plan of M6 uses cannot be deleted. */
public class TemplateDrafts extends DraftService<TemplateDraftInput, TemplateDraft> {
  public TemplateDrafts(DraftStore<TemplateDraftInput, TemplateDraft> store, ObjectMapper json) {
    super(store, json, TemplateDraftInput.class, "Không tìm thấy mẫu kế hoạch.", TemplateDrafts::inUse);
  }

  static ApiException inUse() {
    return new ApiException(HttpStatus.CONFLICT, "TEMPLATE_IN_USE",
        "Mẫu đang được lộ trình tham chiếu nên không thể xóa.");
  }
}
