import { createContext, useContext } from 'react';

export type Me = { username: string; displayName: string; source: string; roles: string[]; effectiveRoles: string[] };

export const MeCtx = createContext<Me | null>(null);

/** Permissões derivadas dos papéis (a API valida de novo; aqui é só para esconder o que não se pode fazer). */
export function usePerm() {
  const me = useContext(MeCtx);
  const has = (r: string) => !!me?.effectiveRoles.includes(r);
  return { me, canEdit: has('OPERATOR'), canApprove: has('APPROVER'), isAdmin: has('ADMIN') };
}
