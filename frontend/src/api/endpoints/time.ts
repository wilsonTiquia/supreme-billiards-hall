import { request } from '../client';
import type { ServerTime } from '../types';

export function fetchServerTime(): Promise<ServerTime> {
  return request<ServerTime>('/time');
}
