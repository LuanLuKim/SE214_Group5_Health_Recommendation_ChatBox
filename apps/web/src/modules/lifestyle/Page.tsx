import { ModulePanel, PendingFeature } from '../../components/ModulePanel';

export default function Page() {
  return (
    <ModulePanel id="lifestyle">
      <div className="feature-grid">
        <PendingFeature
          title="Lộ trình & thói quen"
          description="Chọn mẫu đã duyệt, lọc dị ứng và theo dõi chuỗi ngày duy trì."
        />
        <PendingFeature
          title="Nhắc nhở"
          description="Đặt thời gian, chọn kênh gửi và tắt nhắc nhở khi cần."
        />
      </div>
      <p className="module-note">
        Bản khung chưa bật xử lý dữ liệu cá nhân. Các chức năng sẽ được bổ sung trong module này.
      </p>
    </ModulePanel>
  );
}
