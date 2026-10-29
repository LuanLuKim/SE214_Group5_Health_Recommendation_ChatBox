import type { ComponentType } from 'react';
import type { ModuleId } from '@health/contracts';
import SafetyPage from './safety/Page';
import KnowledgePage from './knowledge/Page';
import HealthPage from './health/Page';
import ChatPage from './chat/Page';
import AccountsPage from './accounts/Page';
import LifestylePage from './lifestyle/Page';

export const modulePages: Record<ModuleId, ComponentType> = {
  safety: SafetyPage,
  knowledge: KnowledgePage,
  health: HealthPage,
  chat: ChatPage,
  accounts: AccountsPage,
  lifestyle: LifestylePage,
};
