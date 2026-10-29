import { ModulePanel, PendingFeature } from '../../components/ModulePanel';

export default function Page() {
  return (
    <ModulePanel id="accounts">
      <div className="feature-grid">
        <PendingFeature
          title="Đăng ký & đăng nhập"
          description="Xác nhận đủ 18 tuổi, đồng ý điều khoản và quản lý phiên đăng nhập."
        />
        <PendingFeature
          title="Tài khoản & phân quyền"
          description="Thông tin cá nhân, xác nhận xóa qua email và vai trò chuyên gia nội dung."
        />
      </div>
      <p className="module-note">
        Bản khung chưa bật xử lý dữ liệu cá nhân. Các chức năng sẽ được bổ sung trong module này.
      </p>
    </ModulePanel>
  );
}
