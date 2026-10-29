import { ModulePanel, PendingFeature } from '../../components/ModulePanel';

export default function Page() {
  return (
    <ModulePanel id="health">
      <div className="feature-grid">
        <PendingFeature
          title="Hồ sơ & chỉ số cá nhân"
          description="Chiều cao, cân nặng, BMI và giờ ngủ do người dùng tự nhập."
        />
        <PendingFeature
          title="Thống kê hệ thống"
          description="Quản trị viên xem số liệu tổng hợp, không xem hội thoại cá nhân."
        />
      </div>
      <p className="module-note">
        Bản khung chưa bật xử lý dữ liệu cá nhân. Các chức năng sẽ được bổ sung trong module này.
      </p>
    </ModulePanel>
  );
}
