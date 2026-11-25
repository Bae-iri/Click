"""OpenAI Responses API contract shared by the app planner and evidence verifier."""
import os
import re


ENDPOINT = 'https://api.openai.com/v1/responses'
DEFAULT_MODEL = 'gpt-6.2-sol'
MODEL_PATTERN = r'gpt-[A-Za-z0-9._-]{1,80}'
OPENROUTER_ENDPOINT = 'https://openrouter.ai/api/v1/responses'
QWEN_FLASH_MODEL = 'qwen/qwen3.8-flash'


def app_task_model():
    model = os.getenv('APP_TASK_OPENAI_MODEL', DEFAULT_MODEL).strip()
    if not re.fullmatch(MODEL_PATTERN, model):
        raise ValueError('Invalid APP_TASK_OPENAI_MODEL')
    return model


def planner_payload(model, prompt, schema):
    # Strict Responses schemas require all properties. The shared parser accepts
    # an empty planned_steps array when the planner has no lookahead.
    strict_schema = dict(schema, required=list(schema['properties']))
    payload = {'model': model, 'input': prompt, 'store': False, 'max_output_tokens': 2048,
               'text': {'format': {'type': 'json_schema', 'name': 'app_task_step',
                                   'strict': True, 'schema': strict_schema}}}
    if model == DEFAULT_MODEL:
        payload['reasoning'] = {'effort': 'low'}
    elif model == QWEN_FLASH_MODEL:
        payload['reasoning'] = {'effort': 'none'}
        # Restrict OpenRouter inference routing; store=False alone is not ZDR.
        # Account privacy settings must also exclude Safety Retention endpoints.
        payload['provider'] = {'zdr': True, 'data_collection': 'deny'}
    return payload


def completed_text(body):
    if body.get('status') != 'completed':
        raise ValueError('incomplete')
    # Reasoning metadata may precede the assistant message. A refusal, tool
    # call, second message or partial content cannot become an Android action.
    items = [item for item in body['output'] if item['type'] != 'reasoning']
    if len(items) != 1:
        raise ValueError('shape')
    message = items[0]
    if (message['type'] != 'message' or message.get('role') != 'assistant'
            or message.get('status', 'completed') != 'completed'):
        raise ValueError('shape')
    part, = message['content']
    if part['type'] != 'output_text' or not isinstance(part.get('text'), str):
        raise ValueError('shape')
    return part['text']
