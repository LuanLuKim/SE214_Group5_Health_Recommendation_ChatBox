import { ModulePanel, PendingFeature } from '../../components/ModulePanel';

export default function Page() {
  return (
    <ModulePanel id="safety">
      <div className="feature-grid">
        <PendingFeature
          title="Đánh giá mức độ cần chú ý"
          description="Đối chiếu quy tắc đã duyệt trước khi tạo câu trả lời AI."
        />
        <PendingFeature
          title="Quản lý quy tắc cảnh báo"
          description="Chuyên gia nội dung soạn điều kiện, nguồn và gửi duyệt."
        />
      </div>
      <p className="module-note">
        Bản khung chưa bật xử lý dữ liệu cá nhân. Các chức năng sẽ được bổ sung trong module này.
      </p>
    </ModulePanel>
  );
}
