import { useState } from "react";
import { Button, DemoNotice, Glyph, PageHeader, Status } from "../components/Ui";
import { users as userFixtures } from "../fixtures";
import { toStatusTone } from "../domain";

export function UsersPage() {
  const [users, setUsers] = useState(userFixtures);
  const toggle = (id: string) => setUsers((items) => items.map((item) => item.id === id && item.id !== "usr-1" ? { ...item, state: item.state === "ENABLED" ? "DISABLED" : "ENABLED" } : item));
  return (
    <div className="page">
      <DemoNotice />
      <PageHeader eyebrow="平台 / 访问治理" title="用户与可组合角色" description="账号只能由管理员创建；Operator、Publisher、Admin 权限按职责组合。" actions={<Button className="button--primary"><Glyph name="plus"/>新增用户</Button>} />
      <div className="role-strip"><div><b>OPERATOR</b><span>管理场景、素材、文档与资产</span></div><div><b>PUBLISHER</b><span>执行发布预检与确认</span></div><div><b>ADMIN</b><span>管理用户、模型和全局模板</span></div></div>
      <section className="user-table" aria-label="用户列表">
        <div className="user-table__head"><span>用户</span><span>角色</span><span>创建时间</span><span>状态</span><span>操作</span></div>
        {users.map((user) => <article key={user.id}>
          <div className="user-identity"><span>{user.name.slice(0, 1)}</span><div><b>{user.name}</b><small>@{user.username}</small></div></div>
          <div className="role-list">{user.roles.length ? user.roles.map((role) => <code key={role}>{role}</code>) : <span>未分配角色</span>}</div>
          <span>{user.createdAt}</span><Status tone={toStatusTone(user.state)}>{user.state === "ENABLED" ? "已启用" : "已停用"}</Status>
          <div className="row-actions"><button>重置密码</button><button disabled={user.id === "usr-1"} onClick={() => toggle(user.id)}>{user.state === "ENABLED" ? "停用" : "启用"}</button></div>
        </article>)}
      </section>
    </div>
  );
}
