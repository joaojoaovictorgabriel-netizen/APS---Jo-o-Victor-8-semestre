"""APS: API REST de monitoramento ambiental. Execute: uvicorn main:app --reload --host 0.0.0.0"""
import os
from datetime import datetime, timezone
from contextlib import asynccontextmanager
from typing import Optional
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Depends, Header
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import create_engine, Column, Integer, Float, String, DateTime, ForeignKey, select
from sqlalchemy.orm import declarative_base, sessionmaker
from passlib.context import CryptContext
from itsdangerous import URLSafeTimedSerializer, BadSignature, SignatureExpired

load_dotenv()
DATABASE_URL = os.getenv('DATABASE_URL', 'sqlite:///./aps_demo.db')
engine = create_engine(DATABASE_URL, connect_args={'check_same_thread': False} if DATABASE_URL.startswith('sqlite') else {}, pool_pre_ping=True)
Session = sessionmaker(bind=engine)
Base = declarative_base()
passwords = CryptContext(schemes=['bcrypt'], deprecated='auto')
serializer = URLSafeTimedSerializer(os.getenv('SECRET_KEY', 'troque-esta-chave-em-producao'))

def verificar_usuario(authorization: Optional[str] = Header(default=None)):
    if not authorization or not authorization.startswith('Bearer '):
        raise HTTPException(401, 'Autenticação necessária')
    try:
        user_id = int(serializer.loads(authorization[7:], max_age=86400))
    except (BadSignature, SignatureExpired, ValueError):
        raise HTTPException(401, 'Sessão inválida ou expirada')
    with Session() as db:
        if db.get(Usuario, user_id) is None: raise HTTPException(401, 'Usuário não encontrado')
    return user_id

class Usuario(Base):
    __tablename__ = 'usuarios'
    id = Column(Integer, primary_key=True)
    email = Column(String(255), unique=True, nullable=False)
    senha_hash = Column(String(255), nullable=False)

class Ponto(Base):
    __tablename__ = 'pontos_monitoramento'
    id = Column(Integer, primary_key=True)
    cidade = Column(String(100), unique=True, nullable=False)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)

class Medicao(Base):
    __tablename__ = 'medicoes'
    id = Column(Integer, primary_key=True)
    ponto_id = Column(Integer, ForeignKey('pontos_monitoramento.id'), nullable=False)
    temperatura = Column(Float)
    umidade = Column(Integer)
    pressao = Column(Float)
    vento = Column(Float)
    pm25 = Column(Float, nullable=True)
    qualidade_ar = Column(String(50))
    classificacao = Column(String(20))
    data_hora = Column(String(40))
    registrada_em = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

class Alerta(Base):
    __tablename__ = 'alertas'
    id = Column(Integer, primary_key=True)
    medicao_id = Column(Integer, ForeignKey('medicoes.id'), nullable=False)
    nivel = Column(String(20), nullable=False)
    mensagem = Column(String(255), nullable=False)
    criada_em = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

PONTOS = [
    ('São Paulo - Centro', -23.5505, -46.6333),
    ('São Bernardo do Campo', -23.6914, -46.5646),
    ('Santo André', -23.6639, -46.5383),
    ('São Caetano do Sul', -23.6229, -46.5548),
]

@asynccontextmanager
async def lifespan(app):
    Base.metadata.create_all(engine)
    with Session() as db:
        for cidade, lat, lon in PONTOS:
            if not db.scalar(select(Ponto).where(Ponto.cidade == cidade)):
                db.add(Ponto(cidade=cidade, latitude=lat, longitude=lon))
        db.commit()
    yield

app = FastAPI(title='APS Monitoramento de Reversão Térmica', lifespan=lifespan)

class Credenciais(BaseModel):
    email: EmailStr
    senha: str = Field(min_length=6)

@app.get('/saude')
def saude(): return {'status': 'ok', 'fonte': 'Open-Meteo'}

@app.post('/cadastro')
def cadastro(body: Credenciais):
    if '@' not in body.email: raise HTTPException(422, 'E-mail inválido')
    with Session() as db:
        if db.scalar(select(Usuario).where(Usuario.email == body.email.lower())): raise HTTPException(409, 'E-mail já cadastrado')
        user = Usuario(email=body.email.lower(), senha_hash=passwords.hash(body.senha))
        db.add(user); db.commit(); db.refresh(user)
        return {'id': user.id, 'email': user.email}

@app.post('/login')
def login(body: Credenciais):
    with Session() as db:
        user = db.scalar(select(Usuario).where(Usuario.email == body.email.lower()))
        if not user or not passwords.verify(body.senha, user.senha_hash): raise HTTPException(401, 'Credenciais inválidas')
        return {'id': user.id, 'email': user.email, 'autenticado': True, 'token': serializer.dumps(user.id)}

@app.get('/localizacoes')
def localizacoes(usuario: int = Depends(verificar_usuario)):
    with Session() as db:
        return [{'id': p.id, 'cidade': p.cidade, 'latitude': p.latitude, 'longitude': p.longitude} for p in db.scalars(select(Ponto).order_by(Ponto.id))]

def serialize(m, p):
    return {'id': m.id, 'cidade': p.cidade, 'latitude': p.latitude, 'longitude': p.longitude,
            'temperatura': m.temperatura, 'umidade': m.umidade, 'pressao': m.pressao, 'vento': m.vento,
            'pm25': m.pm25, 'qualidade_ar': m.qualidade_ar, 'classificacao': m.classificacao, 'data_hora': m.data_hora}

async def coletar(p):
    async with httpx.AsyncClient(timeout=20) as client:
        params = {'latitude': p.latitude, 'longitude': p.longitude, 'current': 'temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m', 'timezone': 'America/Sao_Paulo'}
        r = await client.get('https://api.open-meteo.com/v1/forecast', params=params); r.raise_for_status()
        now = r.json()['current']
        pm = None
        try:
            aq = await client.get('https://air-quality-api.open-meteo.com/v1/air-quality', params={'latitude': p.latitude, 'longitude': p.longitude, 'current': 'pm2_5', 'timezone': 'America/Sao_Paulo'})
            aq.raise_for_status()
            pm = aq.json().get('current', {}).get('pm2_5')
        except (httpx.HTTPError, KeyError, ValueError): pass
    level = 'SEM DADOS' if pm is None else ('ALERTA' if pm > 35 else 'ATENÇÃO' if pm > 15 else 'NORMAL')
    return dict(temperatura=now['temperature_2m'], umidade=now['relative_humidity_2m'], pressao=now['surface_pressure'], vento=now['wind_speed_10m'], pm25=pm, qualidade_ar=('Indisponível' if pm is None else 'PM2.5 baixo' if pm <= 15 else 'PM2.5 moderado' if pm <= 35 else 'PM2.5 elevado'), classificacao=level, data_hora=now['time'])

@app.get('/monitoramentos')
async def monitoramentos(cidade: Optional[str] = None, usuario: int = Depends(verificar_usuario)):
    with Session() as db:
        pontos = db.scalars(select(Ponto).where(Ponto.cidade == cidade) if cidade else select(Ponto)).all()
        if not pontos: raise HTTPException(404, 'Cidade não encontrada')
        saida = []
        for p in pontos:
            try: dados = await coletar(p)
            except (httpx.HTTPError, KeyError, ValueError, TypeError) as exc: raise HTTPException(503, f'Fonte meteorológica indisponível: {exc}')
            # Não duplicar a mesma medição da mesma hora em atualizações sucessivas
            existente = db.scalar(select(Medicao).where(Medicao.ponto_id == p.id, Medicao.data_hora == dados['data_hora']).order_by(Medicao.id.desc()))
            if existente is None:
                existente = Medicao(ponto_id=p.id, **dados); db.add(existente); db.flush()
                if dados['classificacao'] in ('ATENÇÃO', 'ALERTA'):
                    db.add(Alerta(medicao_id=existente.id, nivel=dados['classificacao'], mensagem=f"{p.cidade}: {dados['classificacao']} conforme PM2.5"))
            db.commit(); saida.append(serialize(existente, p))
        return saida

@app.get('/monitoramentos/{medicao_id}')
def monitoramento_detalhe(medicao_id: int, usuario: int = Depends(verificar_usuario)):
    with Session() as db:
        m = db.get(Medicao, medicao_id)
        if not m: raise HTTPException(404, 'Medição não encontrada')
        return serialize(m, db.get(Ponto, m.ponto_id))

@app.get('/historico')
def historico(cidade: Optional[str] = None, limite: int = 50, usuario: int = Depends(verificar_usuario)):
    with Session() as db:
        query = select(Medicao, Ponto).join(Ponto, Medicao.ponto_id == Ponto.id)
        if cidade: query = query.where(Ponto.cidade == cidade)
        rows = db.execute(query.order_by(Medicao.id.desc()).limit(min(max(limite, 1), 200))).all()
        return [serialize(m, p) for m, p in rows]

@app.get('/alertas')
def alertas(cidade: Optional[str] = None, usuario: int = Depends(verificar_usuario)):
    with Session() as db:
        query = select(Alerta, Ponto).join(Medicao, Alerta.medicao_id == Medicao.id).join(Ponto, Medicao.ponto_id == Ponto.id)
        if cidade: query = query.where(Ponto.cidade == cidade)
        return [{'id': a.id, 'cidade': p.cidade, 'nivel': a.nivel, 'mensagem': a.mensagem, 'data_hora': a.criada_em.isoformat()} for a,p in db.execute(query.order_by(Alerta.id.desc()).limit(100))]
