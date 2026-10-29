import { ModulePanel, PendingFeature } from '../../components/ModulePanel';

export default function Page() {
  return (
    <ModulePanel id="chat">
      <div className="feature-grid">
        <PendingFeature
          title="Trò chuyện với trợ lý"
          description="Kết nối Safety Gate, tài liệu đã duyệt và kiểm tra đầu ra."
        />
        <PendingFeature
          title="Lịch sử & phản hồi"
          description="Quản lý hội thoại của mình; tự chọn đính kèm phản hồi ẩn danh."
        />
      </div>
      <p className="module-note">
        Bản khung chưa bật xử lý dữ liệu cá nhân. Các chức năng sẽ được bổ sung trong module này.
      </p>
    </ModulePanel>
  );
}
