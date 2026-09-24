import { request } from '../client';
import type { SetupItem, SetupKind, UUID } from '../types';

export function fetchSetup(kind: SetupKind): Promise<SetupItem[]> {
  return request(`/setup/${kind}`);
}

export function changeSetup(kind: SetupKind, id: UUID, action: 'delete' | 'archive' | 'restore'): Promise<null> {
  return request(`/setup/${kind}/${id}${action === 'delete' ? '' : `/${action}`}`, {
    method: action === 'delete' ? 'DELETE' : 'POST',
  });
}
