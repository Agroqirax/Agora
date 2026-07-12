# Ferramentas do Android

O Agora pode acessar com segurança recursos selecionados do sistema Android quando o modelo precisar deles. Essas ferramentas permitem que o modelo recupere sua localização atual, leia contatos ou interaja com seu calendário, respeitando o sistema de permissões do Android.

## Ferramentas Disponíveis

| Ferramenta | Finalidade |

| ------------ | ----------------------------------------------------- |

| **Localização** | Recuperar a localização aproximada ou precisa do dispositivo |

| **Contatos** | Pesquisar e ler contatos armazenados no dispositivo |

| **Calendário** | Ler eventos futuros e criar novas entradas no calendário |

O modelo detecta automaticamente as ferramentas habilitadas e decide quando elas são úteis durante uma conversa.

## Privacidade e Permissões

As Ferramentas do Android exigem permissões padrão do sistema Android.

Na primeira vez que o modelo tentar usar uma dessas ferramentas, o Agora solicitará a permissão apropriada do Android. As permissões são solicitadas somente quando uma ferramenta é necessária pela primeira vez.

!!! Observação

Você pode revogar as permissões a qualquer momento nas Configurações do Android do seu dispositivo.

## Configuração

1. Acesse **Configurações → Android**
2. Ative as ferramentas que você deseja que o modelo utilize:

- **Localização**

- **Contatos**

- **Calendário**

3. Conceda as permissões do Android solicitadas quando solicitado.

Uma vez ativadas, o modelo poderá acessar essas ferramentas automaticamente sempre que forem úteis durante uma conversa.

## Localização

A ferramenta Localização permite que o modelo determine a localização atual do seu dispositivo.

Usos típicos incluem:

- Encontrar lugares próximos
- Fornecer informações meteorológicas locais
- Recomendações baseadas em localização
- Estimar tempos de viagem
- Responder a perguntas sobre sua área atual

Dependendo das configurações do seu dispositivo e das permissões concedidas, a localização pode ser aproximada ou precisa.

## Contatos

A ferramenta Contatos permite que o modelo pesquise contatos armazenados no seu dispositivo.

Usos típicos incluem:

- Pesquisar números de telefone
- Encontrar endereços de e-mail
- Identificar contatos salvos
- Selecionar contatos para mensagens ou tarefas de comunicação

O modelo acessa apenas as informações de contato necessárias para atender à sua solicitação.

## Calendário

A ferramenta Calendário permite que o modelo leia seu calendário e crie eventos.

Usos típicos incluem:

- Verificar sua agenda
- Listar eventos futuros
- Encontrar horários disponíveis
- Criar compromissos
- Revisar detalhes de reuniões

Criar ou modificar eventos requer permissão de gravação no calendário.

## Segurança

As Ferramentas do Android usam o sistema de permissões integrado do Android.

- Solicita permissão em tempo de execução antes do primeiro uso
- As permissões podem ser revogadas a qualquer momento
- Ferramentas desativadas não podem ser acessadas
- Todo o acesso ocorre localmente por meio da estrutura de permissões do Android

O Agora não pode acessar dados protegidos sem a sua permissão.

## Solução de problemas

### Permissão negada

Se o dispositivo informar que não consegue acessar uma ferramenta:

- Verifique se a ferramenta está ativada em **Configurações → Android**
- Confirme se a permissão necessária do Android foi concedida
- Se necessário, revogue e conceda novamente a permissão nas Configurações do Android

### Localização indisponível

- Certifique-se de que os Serviços de Localização estejam ativados no seu dispositivo
- Mova-se para uma área com melhor cobertura de GPS ou rede
- Conceda a localização precisa se for necessária maior acurácia

### Calendário ou contatos vazios

Verifique se o seu dispositivo contém eventos de calendário ou contatos e se a permissão correspondente do Android foi concedida.
