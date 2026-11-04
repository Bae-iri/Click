from sqlalchemy import Boolean, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.models.user import JSONType


class ApprovedTaskGraph(Base):
    __tablename__ = "approved_task_graphs"
    __table_args__ = (UniqueConstraint("graph_id", "graph_version", name="uq_graph_release"),)
    id: Mapped[str] = mapped_column(String(200), primary_key=True)
    graph_id: Mapped[str] = mapped_column(String(100))
    task_id: Mapped[str] = mapped_column(String(100), index=True)
    goal_type: Mapped[str] = mapped_column(String(200), index=True)
    target_app: Mapped[str] = mapped_column(String(100))
    graph_version: Mapped[str] = mapped_column(String(64))
    graph_hash: Mapped[str] = mapped_column(String(64))
    schema_version: Mapped[str] = mapped_column(String(30))
    body: Mapped[dict] = mapped_column(JSONType)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    approved: Mapped[bool] = mapped_column(Boolean, default=True)
    priority: Mapped[int] = mapped_column(Integer, default=100)
