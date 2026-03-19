// aiManager.js - AI 매칭 및 통계 관리 모듈

const AI_TYPES = ['ALPHA', 'BETA'];

// localStorage 기반 AI 통계
function getAiStats() {
    const raw = localStorage.getItem('aiStats_v1');
    if (!raw) {
        const init = { ALPHA: { wins: 0, games: 0 }, BETA: { wins: 0, games: 0 } };
        localStorage.setItem('aiStats_v1', JSON.stringify(init));
        return init;
    }
    try {
        return JSON.parse(raw);
    } catch (e) {
        const init = { ALPHA: { wins: 0, games: 0 }, BETA: { wins: 0, games: 0 } };
        localStorage.setItem('aiStats_v1', JSON.stringify(init));
        return init;
    }
}

function saveAiStats(stats) {
    localStorage.setItem('aiStats_v1', JSON.stringify(stats));
}

function updateAiStats(aiType, aiWon) {
    const stats = getAiStats();
    if (!stats[aiType]) stats[aiType] = { wins: 0, games: 0 };
    stats[aiType].games += 1;
    if (aiWon) stats[aiType].wins += 1;
    saveAiStats(stats);
}

function selectBestAi() {
    const s = getAiStats();
    const a = s.ALPHA, b = s.BETA;
    const aRate = (a.games === 0) ? 0 : (a.wins / a.games);
    const bRate = (b.games === 0) ? 0 : (b.wins / b.games);
    return (aRate >= bRate) ? 'ALPHA' : 'BETA';
}

// 현재 매치 정보
let currentMatchMode = 'HUMAN'; // 'HUMAN' or 'AI'
let currentAiType = null;       // AI 사용 시 AI 타입

function setMatchMode(mode, aiType = null) {
    currentMatchMode = mode;
    currentAiType = aiType;
}

function getMatchMode() {
    return currentMatchMode;
}

function getAiType() {
    return currentAiType;
}

function resetMatchMode() {
    currentMatchMode = 'HUMAN';
    currentAiType = null;
}
