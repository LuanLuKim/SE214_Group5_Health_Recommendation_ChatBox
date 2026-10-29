import { modules, type ModuleId } from '@health/contracts';
import type { ReactNode } from 'react';

export function ModulePanel({ id, children }: { id: ModuleId; children: ReactNode }) {
  const module = modules.find((item) => item.id === id)!;
  return (
    <section aria-labelledby={`heading-${id}`}>
      <div className="eyebrow">MODULE {String(module.number).padStart(2, '0')}</div>
      <h1 id={`heading-${id}`}>{module.title}</h1>
      <p className="lead">{module.description}</p>
      <div className="case-list" aria-label="Use case">
        {module.useCases.map((uc) => (
          <span key={uc}>{uc}</span>
        ))}
      </div>
      {children}
    </section>
  );
}

export function PendingFeature({ title, description }: { title: string; description: string }) {
  return (
    <article className="feature">
      <div className="feature-top">
        <h3>{title}</h3>
        <span className="status">Chờ triển khai</span>
      </div>
      <p>{description}</p>
    </article>
  );
}
