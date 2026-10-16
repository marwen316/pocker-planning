export type Role = "DEVELOPER" | "VISITOR";
export interface Participant {
  id: string;
  firstName: string;
  lastName: string;
  role: Role;
  voted: boolean;
  card: string | null;
  self: boolean;
}
export interface Session {
  revision: number;
  topic: string;
  round: number;
  revealed: boolean;
  canReveal: boolean;
  participants: Participant[];
  cards: string[];
}
