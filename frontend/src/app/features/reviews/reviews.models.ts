export interface Review {
  id: number;
  code_snippet: string;
  language: string;
  ai_response: string;
  relevant_standard: string;
  relevance_score: number;
  status: string;
  created_at: string;
}

export interface ReviewRequest {
  code_snippet: string;
  language: string;
}

export interface ReviewSection {
  title: string;
  content: string;
}
