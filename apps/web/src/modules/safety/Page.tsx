import { ModulePanel, PendingFeature } from '../../components/ModulePanel';
import { RulesWorkspace } from './RulesWorkspace';

export default function Page() {
  return (
    <ModulePanel id="safety">
      <RulesWorkspace />
      <div className="feature-grid rules-pending">
        <PendingFeature
          title="Đánh giá mức độ cần chú ý"
          description="Đối chiếu quy tắc đã duyệt trước khi tạo câu trả lời AI."
        />
      </div>
    </ModulePanel>
  );
}
